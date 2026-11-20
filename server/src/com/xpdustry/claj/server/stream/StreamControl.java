/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2026  Xpdustry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xpdustry.claj.server.stream;

import static com.xpdustry.claj.server.stream.StreamProtocol.*;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

import arc.struct.LongMap;
import arc.util.Log;
import arc.util.io.ByteBufferInput;

import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.CloseReason;
import com.xpdustry.claj.common.status.RejectReason;
import com.xpdustry.claj.server.ClajConfig;
import com.xpdustry.claj.server.ClajConnection;
import com.xpdustry.claj.server.ClajRelay;
import com.xpdustry.claj.server.ClajRoom;


/**
 * Stage 2 requests and the {@code CONTROL} messages of hosts, decoded with the {@code common} packets and decided
 * by {@link ClajRelay}'s handlers, as for 2.x connections. This adds what only the stream has: the memory budget,
 * the pipelining rule, and the requests over UDP.
 */
public class StreamControl {
  /** Bytes of one room list entry without its state: roomId, isProtected and the state length. */
  public static final int LIST_ENTRY_SIZE = Long.BYTES + 1 + Short.BYTES;
  /** Bytes of a room list without entries: type and entry count. */
  public static final int LIST_HEADER_SIZE = 1 + Integer.BYTES;

  protected final StreamRelay relay;
  protected final ClajRelay server;
  protected final ByteBufferInput input = new ByteBufferInput();

  protected final RoomInfoRequestPacket infoRequest = new RoomInfoRequestPacket();
  protected final RoomListRequestPacket listRequest = new RoomListRequestPacket();
  protected final RoomJoinRequestPacket joinRequest = new RoomJoinRequestPacket();
  protected final RoomCreationRequestPacket createRequest = new RoomCreationRequestPacket();
  protected final RoomStatePacket stateRequest = new RoomStatePacket();
  protected final ServerInfoPacket serverInfo = new ServerInfoPacket();
  protected final RoomListPacket roomList = new RoomListPacket();
  protected final RoomClosedPacket roomClosed = new RoomClosedPacket();

  StreamControl(StreamRelay relay) {
    this.relay = relay;
    this.server = relay.server;
  }

  // region stage 2

  /**
   * Handles one request. The body must be consumed exactly, malformed input throws.
   * @param pipelined whether bytes follow a transition request, which is then refused.
   */
  public void request(StreamConnection con, int type, ByteBuffer body, boolean pipelined) throws ProtocolException {
    input.setBuffer(body);
    ClajConnection claj = con.claj;
    switch (type) {
      case VERSION_REQUEST:
        serverInfo.version = ClajConfig.serverVersion;
        relay.send(con, serverInfo);
        break;
      case ROOM_INFO_REQUEST:
        infoRequest.read(input);
        server.onInfoRequest(claj, infoRequest.roomId);
        break;
      case ROOM_LIST_REQUEST:
        listRequest.read(input);
        roomList(con);
        break;
      case JOIN_REQUEST:
        joinRequest.read(input);
        join(con, pipelined);
        break;
      case ROOM_CONFIG:
        // No reply, it applies to the room created next.
        RoomConfigPacket config = new RoomConfigPacket();
        config.read(input);
        if (relay.udpRequest) {
          // Without a reply, nothing orders it before a ROOM_CREATE over UDP: a password could silently be
          // missing. So it is only accepted over TCP, and refused out loud over UDP, nothing applied.
          roomClosed.reason = CloseReason.error;
          relay.send(con, roomClosed);
          break;
        }
        con.config = config;
        break;
      case ROOM_CREATE:
        createRequest.read(input);
        create(con, pipelined);
        break;
      default:
        throw new ProtocolException("unexpected request type " + type);
    }
  }

  /** Lists the public stream rooms of the type, as many as fit in one reply and in the budget. */
  public void roomList(StreamConnection con) {
    if (!server.routines.getAddressRate(con.claj).allowList()) {
      server.rejectRoomList(con.claj, listRequest.type, true);
      return;
    }
    roomList.clear(false);
    // Over UDP, as many as fit in one datagram, which is never queued nor reserved.
    int size = 1 + LIST_HEADER_SIZE, max = relay.udpRequest ? MAX_DATAGRAM_SIZE : MAX_REPLY_SIZE;
    LongMap<ClajRoom> rooms = listRequest.type == null ? null : server.types.get(listRequest.type);
    if (rooms != null) {
      for (ClajRoom room : rooms.values()) {
        if (!room.isPublic || !room.isStream() || room.isClosed()) continue;
        int entry = LIST_ENTRY_SIZE + (room.rawState == null ? 0 : room.rawState.remaining());
        if (size + entry > max) break;
        size += entry;
        roomList.states.put(room.id, room.rawState);
        if (room.isProtected) roomList.protectedRooms.add(room.id);
      }
    }
    int extra = size - StreamRelay.PENDING_RESERVE;
    if (extra > 0 && !relay.udpRequest) {
      if (relay.reserve(con, extra)) con.replyReserved = extra;
      else roomList.clear(false); // Over budget: an empty list is still a valid answer.
    }
    relay.send(con, roomList);
    roomList.clear(false);
    Log.debug("Connection @ requested room list of type @.", con.claj.sid, listRequest.type);
  }

  public void join(StreamConnection con, boolean pipelined) {
    RoomJoinRequestPacket request = joinRequest;
    if (pipelined) {
      // The relay can't know where the stream would start: refused, then closed.
      server.rejectRoomJoin(con.claj, request.roomId, RejectReason.error);
      relay.linger(con);
      return;
    } else if (!relay.reserve(con, relay.streamReserve)) {
      server.rejectRoomJoin(con.claj, request.roomId, RejectReason.serverFull);
      return;
    }
    RejectReason reason = server.onRoomJoin(con.claj, true, request.roomId, request.type, request.withPassword,
                                            request.password);
    // Accepted, so JOIN_ACCEPTED is queued: the stream starts with the client's arrival in the room.
    if (reason == null) {
      ClajRoom room = server.getRoom(request.roomId);
      if (server.addClient(room, con.claj)) {
        relay.info("Connection @ joined the room @. (type: @)", con.claj.sid, room.sid, request.type);
        return;
      }
      relay.linger(con); // Accepted but the room closed meanwhile: the client reads an EOF.
    }
    relay.unreserve(con, relay.streamReserve);
  }

  public void create(StreamConnection con, boolean pipelined) {
    RoomCreationRequestPacket request = createRequest;
    CloseReason reason = null;
    if (pipelined) reason = CloseReason.error;
    else if (request.version < 0 || request.type == null) reason = CloseReason.obsoleteClient;
    else if (!relay.reserve(con, relay.hostReserve)) reason = CloseReason.serverFull;
    if (reason != null) {
      server.rejectRoomCreation(con.claj, reason);
      if (pipelined) relay.linger(con);
      return;
    }
    if (server.onRoomCreate(con.claj, request.version, request.type) != null) {
      relay.unreserve(con, relay.hostReserve);
      return;
    }
    RoomConfigPacket config = con.config;
    con.config = null;
    if (config != null) configure(con.room, config);
  }


  // end region
  // region host control

  /** Handles a {@code CONTROL} payload of a host. Malformed input throws, which closes the room. */
  public void host(StreamRoom room, ByteBuffer payload) throws ProtocolException {
    int type = payload.get() & 0xff;
    input.setBuffer(payload);
    try {
      switch (type) {
        case ROOM_CONFIG:
          RoomConfigPacket config = new RoomConfigPacket();
          config.read(input);
          configure(room, config);
          break;
        case ROOM_STATE:
          // The stream wire allows no more, where 2.x truncates: checked before the packet reads it.
          int length = payload.getShort(payload.position()) & 0xffff;
          if (length > RoomStatePacket.MAX_BUFF_SIZE) throw new ProtocolException("ROOM_STATE of " + length + " bytes");
          stateRequest.read(input);
          server.onRoomState(room.host, stateRequest.state);
          break;
        case ROOM_CLOSE:
          server.closeRoom(room, CloseReason.closed);
          relay.info("Room @ closed by connection @ (the host).", room.sid, room.host.sid);
          break;
        default:
          throw new ProtocolException("unexpected control type " + type);
      }
    } catch (RuntimeException e) {
      throw new ProtocolException("malformed control " + type + ": " + e);
    }
    if (payload.hasRemaining()) throw new ProtocolException("trailing bytes in control " + type);
  }

  public void configure(StreamRoom room, RoomConfigPacket config) {
    server.onRoomConfig(room.host, config.isPublic, config.isProtected, config.password, false, config.maxClients);
  }

  // end region
}
