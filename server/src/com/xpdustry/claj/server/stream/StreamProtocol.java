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

import arc.struct.ObjectIntMap;

import com.xpdustry.claj.common.packets.*;


/** Wire constants of the stream relay, big-endian. The specification is {@code stream-notes/protocol.md}. */
public class StreamProtocol {
  /** Version of this wire protocol, sent in {@link #HELLO}. */
  public static final int VERSION = 1;
  /** Size of the length prefix of a stage 1-2 message. */
  public static final int LENGTH_SIZE = 2;
  /** Largest stage 1-2 message a peer may send, type included. The largest one, a join request, is 29 bytes. */
  public static final int MAX_REQUEST_SIZE = 128;
  /** Largest stage 1-2 message the server may send, type included. Only a room list gets near it. */
  public static final int MAX_REPLY_SIZE = 0xffff;

  // region stage 1: hello

  /** Server to peer, right after accept: {@code u8 version, i64 token}. */
  public static final int HELLO = 1;
  /** Peer to server over TCP: {@code i64 token}. Confirms a TCP-only connection. */
  public static final int HELLO_TCP = 2;
  /** Peer to server, a UDP datagram: {@code u8 type, i64 token}. Associates its source address, resent until {@link #WELCOME}. */
  public static final int HELLO_UDP = 3;
  /** Server to peer: {@code u8 flags}, see {@link #FLAG_UDP}. Ends stage 1. */
  public static final int WELCOME = 4;
  /** In {@link #WELCOME} and {@link #OPEN}: the connection has an associated UDP address. */
  public static final int FLAG_UDP = 1;

  // end region
  // region stage 2: control

  /** Peer to server, empty. Answered with {@link #SERVER_INFO}. */
  public static final int VERSION_REQUEST = 10;
  /** Server to peer: {@code ServerInfoPacket}, the CLaJ major version of the server. */
  public static final int SERVER_INFO = 11;
  /** Peer to server: {@code RoomInfoRequestPacket}. Answered with {@link #ROOM_INFO} or {@link #ROOM_INFO_DENIED}. */
  public static final int ROOM_INFO_REQUEST = 12;
  /** Server to peer: {@code RoomInfoPacket}. */
  public static final int ROOM_INFO = 13;
  /** Server to peer, empty: the room doesn't exist. */
  public static final int ROOM_INFO_DENIED = 14;
  /** Peer to server: {@code RoomListRequestPacket}. Answered with {@link #ROOM_LIST}. */
  public static final int ROOM_LIST_REQUEST = 15;
  /** Server to peer: {@code RoomListPacket} of the public rooms of the requested type, truncated to fit. */
  public static final int ROOM_LIST = 16;
  /** Peer to server: {@code RoomJoinRequestPacket}. Answered with {@link #JOIN_ACCEPTED} or {@link #JOIN_DENIED}. */
  public static final int JOIN_REQUEST = 17;
  /** Server to client: {@code RoomJoinAcceptedPacket}. The last framed message, next bytes are the stream. */
  public static final int JOIN_ACCEPTED = 18;
  /** Server to peer: {@code RoomJoinDeniedPacket}. The connection stays in stage 2. */
  public static final int JOIN_DENIED = 19;
  /** Peer to server: {@code RoomConfigPacket}, for the next {@link #ROOM_CREATE}, or in a {@link #CONTROL} frame. */
  public static final int ROOM_CONFIG = 20;
  /** Peer to server: {@code RoomCreationRequestPacket}. Answered with {@link #ROOM_CREATED} or {@link #ROOM_CLOSED}. */
  public static final int ROOM_CREATE = 21;
  /** Server to host: {@code RoomLinkPacket}. The last framed message, next bytes are mux frames. */
  public static final int ROOM_CREATED = 22;
  /** Server to host: {@code RoomClosedPacket}. A refused creation in stage 2, or a room closed by the server. */
  public static final int ROOM_CLOSED = 23;
  /** Host to server, in a {@link #CONTROL} frame: {@code RoomStatePacket}, shown in room info and list. */
  public static final int ROOM_STATE = 24;
  /** Host to server, in a {@link #CONTROL} frame, empty: closes the room. */
  public static final int ROOM_CLOSE = 25;

  // end region
  // region stage 3: host mux

  /** Mux frame header: {@code u8 type, i32 conId, u16 length}, then {@code length} payload bytes. */
  public static final int HEADER_SIZE = 7;
  /** Destination conId of a host {@link #DATA} or {@link #DGRAM} sent to every acknowledged stream. */
  public static final int BROADCAST = 0;
  /** conId of {@link #CONTROL} frames, which belong to the room. */
  public static final int ROOM = 0;

  /** Server to host: a client joined. Payload {@code u8 flags, i64 addressHash}, see {@link #FLAG_UDP}. */
  public static final int OPEN = 1;
  /** Both ways: stream bytes, 1..65535 per frame, consuming credit. The server sends at most its slice size. */
  public static final int DATA = 2;
  /**
   * Aborts the stream in both directions, bytes already accepted are still delivered. From the server:
   * {@code u8 reason}, the {@link arc.net.DcReason} ordinal, like the 2.x {@code ConnectionClosedPacket}.
   */
  public static final int CLOSE = 3;
  /** Both ways: {@code i32 credit}, more bytes the receiver accepts on that stream. */
  public static final int WINDOW = 4;
  /** Both ways, empty: no more {@link #DATA} in this direction, after the queued bytes (TCP half-close). */
  public static final int FIN = 5;
  /** Both ways, on {@link #ROOM}: a stage 2 message ({@code u8 type} and body) for the room. */
  public static final int CONTROL = 6;
  /** Both ways: one datagram, not credited, dropped by the server when the host is behind. */
  public static final int DGRAM = 7;
  /** Host to server, on {@link #ROOM}, empty: a keep-alive for an idle host, like Arc's every 8 s. */
  public static final int PING = 8;

  /** Payload size of {@link #OPEN}. */
  public static final int OPEN_SIZE = 1 + 8;
  /** Payload size of {@link #CLOSE} from the server. */
  public static final int CLOSE_PAYLOAD_SIZE = 1;
  /** Payload size of {@link #WINDOW}. */
  public static final int WINDOW_PAYLOAD_SIZE = 4;
  /** Largest {@link #CONTROL} payload: a room state of 1 KiB with its type and length. */
  public static final int MAX_CONTROL_SIZE = 2048;
  /** Largest datagram payload the server forwards, either way. */
  public static final int MAX_DATAGRAM_SIZE = 4096;
  /** Size of the {@code i32 conId} prefix of a datagram between the server and a host. */
  public static final int DATAGRAM_PREFIX_SIZE = 4;

  /** Credit window per stream per direction. A grant never makes the credit exceed it. */
  public static final int WINDOW_SIZE = 64 * 1024;
  /** Default largest {@link #DATA} the server sends in one turn of a stream, see {@code ClajConfig#sliceSize}. */
  public static final int SLICE_SIZE = 4 * 1024;

  // end region

  private StreamProtocol() {}

  /** Stage 2 type of the packets the server replies with, by class. */
  protected static final ObjectIntMap<Class<?>> replies = new ObjectIntMap<>();
  static {
    replies.put(ServerInfoPacket.class, SERVER_INFO);
    replies.put(RoomInfoPacket.class, ROOM_INFO);
    replies.put(RoomInfoDeniedPacket.class, ROOM_INFO_DENIED);
    replies.put(RoomListPacket.class, ROOM_LIST);
    replies.put(RoomJoinAcceptedPacket.class, JOIN_ACCEPTED);
    replies.put(RoomJoinDeniedPacket.class, JOIN_DENIED);
    replies.put(RoomLinkPacket.class, ROOM_CREATED);
    replies.put(RoomClosedPacket.class, ROOM_CLOSED);
  }

  /** @return the stage 2 type of a reply packet, {@code 0} if it has none on the stream wire. */
  public static int typeOf(Packet packet) {
    return replies.get(packet.getClass(), 0);
  }
}
