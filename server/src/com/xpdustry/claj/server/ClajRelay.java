/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2025-2026  Xpdustry
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

package com.xpdustry.claj.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import arc.*;
import arc.net.*;
import arc.struct.*;
import arc.util.*;

import com.xpdustry.claj.common.ClajNet;
import com.xpdustry.claj.common.ClajPackets.*;
import com.xpdustry.claj.common.net.*;
import com.xpdustry.claj.common.net.stream.*;
import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.*;
import com.xpdustry.claj.common.util.AddressUtil;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.server.ClajEvents.*;
import com.xpdustry.claj.server.stream.StreamConnection;
import com.xpdustry.claj.server.util.ByteRate;
import com.xpdustry.claj.server.util.NetListenerEvent;
import com.xpdustry.claj.server.util.NetworkSpeed;
import com.xpdustry.claj.server.util.NioUtils;


//TODO: ai saying that a udp connection can be hijacked to close a room due to an arcnet design flaw.
/** CLaJ server main class that doing all the stuff. */
public class ClajRelay extends Server implements ApplicationListener, NetListenerFilter {
  /** Size of the connections write buffer, in bytes. */
  public static final int WRITE_BUFFER_SIZE = /*131072*/32768;
  /** Least bytes a 2.x packet costs to the rate limit, so a flood of small packets is limited too. */
  public static final int MIN_PACKET_COST = 1000;

  protected boolean closed, stopping, running;
  public boolean blockCreate, blockJoin;
  /** Port to bind this server. */
  public final int port;
  /** Read/Write speed. */
  public final NetworkSpeed networkSpeed, packetCounter;
  /** Server packet receiver. */
  public final ServerReceiver receiver;
  /** Server routines that manages cache and cleaning things. */
  public final ClajRoutines routines;
  /** List of valid connections. DO NOT EDIT MANUALLY! */
  public final IntMap<ClajConnection> connections = new IntMap<>();
  /** List of created rooms. DO NOT EDIT MANUALLY! */
  public final LongMap<ClajRoom> rooms = new LongMap<>();
  /** Rooms sorted by type. DO NOT EDIT MANUALLY! */
  public final ObjectMap<ClajType, LongMap<ClajRoom>> types = new ObjectMap<>(8);
  /** As the server is very talkative, here is a field to shut up info and warn logs for a time. */
  public volatile boolean beQuiet;
  /** Total number of clients in rooms. */
  protected int clientsInRooms;
  /** Use a non-predictable random for room ids. */
  protected final SecureRandom random = new SecureRandom();

  // Caches
  /** Number of accepted connections per address. */
  protected final ObjectIntMap<InetAddress> addressConnections = new ObjectIntMap<>();
  /** Connections awaiting their registration. */
  protected final IntMap<Connection> pendingConnections;
  private final IntSet lastPending = new IntSet();
  private long lastPendingCheck;
  /** As server version will not change at runtime, cache the serialized packet to avoid re-serialization. */
  private ByteBuffer versionBuff;
  /** Empty room list to send to client requesting no type or a not found one. */
  private final RoomListPacket emptyList = new RoomListPacket().clear(true);

  public ClajRelay(int port) { this(port, false); }
  public ClajRelay(int port, boolean withCounters) { this(port, new ClajServerSerializer(), withCounters); }
  ClajRelay(int port, ClajServerSerializer serializer, boolean withCounters) {
    super(WRITE_BUFFER_SIZE, 32768, serializer);
    this.port = port;
    if (withCounters) {
      serializer.networkSpeed = networkSpeed = new NetworkSpeed();
      serializer.packetCounter = packetCounter = new NetworkSpeed();
    } else networkSpeed = packetCounter = null;
    receiver = new ServerReceiver(this, this);
    routines = new ClajRoutines();

    // Hack to avoid {@link ClajRoom#CON_BROADCAST} from being selected as valid id.
    // But in theory, it doesn't really matter, as for an host, everything will work fine.
    // And for a client, it will not be able to join a room, and will have to reconnect.
    pendingConnections = Reflect.get(Server.class, this, "pendingConnections");
    pendingConnections.put(ClajRoom.CON_BROADCAST, null);

    // Optimize selector by one that does not allocate nodes and iterators
    try {
      Selector selector = Reflect.get(Server.class, this, "selector");
      Reflect.set(Server.class, this, "selector", NioUtils.newOptimizedSelector());
      selector.close();
      Log.info("&gSelector successfully optimized.");
    } catch (Exception e) {
      Log.warn("Unable to optimize selector: @. Skipping...",
              (e instanceof RuntimeException ? e.getCause() : e).toString());
      Log.debug(Strings.getStackTrace(e));
    }

    // Another packets optimization. Packets are reads on main thread
    if (ClajConfig.dualThread.get()) {
      removeListener(receiver);
      serializer.decodeClaj = false;
      addListener(new NetListener() {
        NetListenerFilter filter, modified;
        @SuppressWarnings("hiding")
        final NetSerializer serializer = new ClajServerSerializer();

        public NetListenerFilter getFilter() {
          // Suppress receiver filter to only keep allowReceived()
          NetListenerFilter f = receiver.getFilter();
          if (f != filter && f != modified) {
            filter = f;
            receiver.setFilter(modified = new NetListenerFilter() {
              public boolean allowReceived(Connection connection, Object object) {
                return f.allowReceived(connection, object);
              }
            });
          }
          return filter;
        }

        @Override
        public void connected(Connection connection) {
          if (!getFilter().allowConnected(connection)) return;
          Core.app.post(NetListenerEvent.ofConnected(connection, receiver));
        }

        @Override
        public void disconnected(Connection connection, DcReason reason) {
          if (!getFilter().allowDisconnected(connection, reason)) return;
          Core.app.post(NetListenerEvent.ofDisconnected(connection, receiver, reason));
        }

        @Override
        public void received(Connection connection, Object object) {
          // Do not filter now
          ByteBuffer buffer;
          if (object instanceof RawPacket raw) buffer = raw.data();
          else if (object instanceof ByteBuffer buf) buffer = buf;
          else if (object instanceof FrameworkMessage) return; // We don't care of framework messages
          else {
            Core.app.post(NetListenerEvent.ofReceived(connection, receiver, object));
            return;
          }
          Core.app.post(NetListenerEvent.ofReceived(connection, receiver, buffer, serializer));
        }

        @Override
        public void idle(Connection connection) {
          if (!getFilter().allowIdle(connection)) return;
          Core.app.post(NetListenerEvent.ofIdle(connection, receiver));
        }
      });

    } else {
      //TODO: redirect Core.app.post() calls here
    }

    // Reject blacklisted addresses before allocating the connection buffers
    setConnectFilter(address -> {
      //TODO: not thread-safe
      boolean blacklisted = ClajConfig.blacklist.contains(address);
      if (blacklisted) warn("Rejected client @ for a blacklisted address.", address);
      return !blacklisted;
    });

    setDiscoveryHandler((_, r) -> {
      if (versionBuff == null)
        versionBuff = ByteBuffer.allocate(5).put(ClajNet.id).putInt(ClajConfig.serverVersion);
      r.respond((ByteBuffer)versionBuff.rewind()); // Only safe because multicast is disabled
    });

    receiver.handle(Connect.class, c -> onConnect(toClajCon(c)));
    receiver.handle(Disconnect.class, (c, p) -> onDisconnect(toClajCon(c), p.reason));
    receiver.handle(Idle.class, c -> onIdle(toClajCon(c)));

    receiver.handle(RoomCreationRequestPacket.class, (c, p) -> onRoomCreate(toClajCon(c), p.version, p.type));
    receiver.handle(RoomClosureRequestPacket.class, c -> onRoomClose(toClajCon(c)));
    receiver.handle(RoomJoinPacket.class, (c, p) ->
      onRoomJoin(toClajCon(c), false, p.roomId, p.type, p.withPassword, p.password));
    receiver.handle(RoomJoinRequestPacket.class, (c, p) ->
      onRoomJoin(toClajCon(c), true, p.roomId, p.type, p.withPassword, p.password));
    receiver.handle(RoomConfigPacket.class, (c, p) ->
      onRoomConfig(toClajCon(c), p.isPublic, p.isProtected, p.password, p.requestState, p.maxClients));
    receiver.handle(RoomStatePacket.class, (c, p) -> onRoomState(toClajCon(c), p.state));
    receiver.handle(RoomInfoRequestPacket.class, (c, p) -> onInfoRequest(toClajCon(c), p.roomId));
    receiver.handle(RoomListRequestPacket.class, (c, p) -> onListRequest(toClajCon(c), p.type));

    receiver.handle(ConnectionClosedPacket.class, (c, p) -> onConClose(toClajCon(c), p.conID, p.reason));
    receiver.handle(ConnectionPayloadPacket.class, (c, p) -> onHostPacket(toClajCon(c), p));
    receiver.handle(RawPacket.class, (c, p) -> onConPacket(toClajCon(c), p));
  }

  // region logging

  protected void info(String text, Object... args) { if (!beQuiet) Log.info(text, args); }
  protected void warn(String text, Object... args) { if (!beQuiet) Log.warn(text, args); }

  // end region
  // region filtering

  /** Will also prepare the connection if valid. Called from network thread. */
  @Override
  public boolean allowConnected(Connection connection) {
    if (connection == null || toClajCon(connection) != null) return false;
    String id = AddressUtil.encodeId(connection);
    InetAddress address = AddressUtil.get(connection);
    String ip = address == null ? null : address.getHostAddress();
    connection.setName("Connection " + id); // fix id format in stacktraces

    String refusal = refusal(address, ip);
    if (refusal != null) {
      connection.close(DcReason.closed);
      warn("Connection @ (@) rejected " + refusal, id, ip);
      return false;
    }

    Log.debug("Connection @ (@) received.", id, ip);
    connection.setArbitraryData(new ClajConnection(connection, address, ip, id));
    return true;
  }

  /**
   * Checks a new connection of any transport, and counts its address if accepted.
   * @return why it is refused, {@code null} if accepted.
   */
  public String refusal(InetAddress address, String ip) {
    if (isClosed() || ip == null || ClajConfig.blacklist.contains(ip))
      return isClosed() ? "because of a closed server." : "for a blacklisted address.";
    int max = ClajConfig.maxConnections.get();
    if (max > 0 && connections.size >= max) // Not exact but it's enough
      return "because the server is full.";
    else if (count(address, ClajConfig.connectionLimit.get()))
      return "because the address has too many connections.";
    return null;
  }

  protected boolean count(InetAddress address, int limit) {
    synchronized (addressConnections) {
      // Always counted, in case of the limit is enabled at runtime
      boolean tooMany = addressConnections.increment(address, 0, 1) >= limit && limit > 0;
      // The other connections are still counted
      if (tooMany) addressConnections.increment(address, 0, -1);
      return tooMany;
    }
  }

  protected void uncount(ClajConnection con) {
    synchronized (addressConnections) {
      if (con.uncounted) return; // Can be called twice
      con.uncounted = true;
      if (addressConnections.increment(con.address, 0, -1) <= 1)
        addressConnections.remove(con.address, 0);
    }
  }

  /** {@link #uncount} for the stream relay, in another package, so that the method stays protected. */
  public void uncountStream(ClajConnection con) {
    uncount(con);
  }

  /** Weird name =/. Called from network thread. */
  @Override
  public boolean allowDisconnected(Connection connection, DcReason reason) {
    if (connection == null) return false;
    ClajConnection con = toClajCon(connection);
    boolean valid = con != null;
    String id = valid ? con.sid : AddressUtil.encodeId(connection);
    String ip = valid ? con.saddress : AddressUtil.getString(connection);
    Log.debug("Connection @ (@) lost: @.", id, ip, reason);

    if (valid) uncount(con);
    // Reset streams if needed
    if (StreamReceiver.has(connection)) StreamReceiver.reset(connection);
    // Avoid searching for a room if it was an invalid connection or just a ping
    return valid;
  }

  @Override
  public boolean allowReceived(Connection connection, Object object) {
    ClajConnection con = toClajCon(connection);
    if (con == null) return false;
    // Compatibility with the xzxADIxzx's version
    if (object instanceof String) {
      rejectObsoleteClient(con);
      return false;
    }
    return checkRateLimit(con, sizeOf(object));
  }

  /** @return the bytes a 2.x packet costs to the rate limit: its payload, at least {@link #MIN_PACKET_COST}. */
  public static int sizeOf(Object object) {
    int size = 0;
    if (object instanceof RawPacket raw) size = raw.data().remaining();
    else if (object instanceof ConnectionPayloadPacket wrap && wrap.raw != null) size = wrap.raw.data().remaining();
    return Math.max(size, MIN_PACKET_COST);
  }

  /** We don't need that for moment. Will be useful for back-pressure. called from network thread. */
  @Override
  public boolean allowIdle(Connection connection) {
    return false;
  }

  // end region
  // region events

  public void onConnect(ClajConnection connection) {
    if (connection == null) return;
    // Arc draws its ids at random and the stream relay takes free ones, so an Arc id can meet a stream one.
    ClajConnection other = connections.get(connection.id);
    if (other != null && other != connection) {
      rejectDuplicateId(connection);
      return;
    }
    connections.put(connection.id, connection);
    Events.fire(new ClientConnectedEvent(connection));
  }

  /** Closes a connection whose id is already taken, before anything of it reaches the rooms. */
  protected void rejectDuplicateId(ClajConnection connection) {
    uncount(connection);
    connection.connection.setArbitraryData(null); // Its next events are ignored, see toClajCon()
    connection.close(DcReason.error);
    warn("Connection @ (@) rejected because its id is already taken.", connection.sid, connection.saddress);
  }

  /** The cleanup is done even if an event handler throws, the error is thrown after it. */
  public void onDisconnect(ClajConnection connection, DcReason reason) {
    if (connection == null) return;
    try { Events.fire(new ClientDisonnectedEvent(connection, reason)); }
    finally { disconnected(connection, reason); }
  }

  protected void disconnected(ClajConnection connection, DcReason reason) {
    if (connections.get(connection.id) == connection) connections.remove(connection.id);
    connection.clearQueue();

    ClajRoom room = connection.currentRoom();
    boolean wasClosed = room != null && room.isClosed();
    if (removeClient(connection, reason)){
      info("Room @ closed because connection @ (the host) has disconnected.", room.sid, connection.sid);
      return;
    } else if (room == null) return;
    if (!wasClosed && room.isClosed())
      Log.err("Failed to remove connection @ from room @. The room has been closed", connection.sid, room.sid);
    else info("Connection @ left the room @.", connection.sid, room.sid);
  }

  public void onIdle(ClajConnection connection) {
    if (connection == null) return;
    ClajRoom room = connection.currentRoom();
    if (room != null) room.idle(connection);
    // No event for that, this is received to many times
  }

  /** @return not {@code null} if action was denied. */
  public CloseReason onRoomCreate(ClajConnection connection, int version, ClajType type) {
    if (connection == null) return CloseReason.error;
    // Ignore room creation requests when the server is closing
    if (isClosed() || blockCreate) {
      rejectRoomCreation(connection, CloseReason.serverClosed);
      warn("Connection @ tried to create a room but " +
           (blockCreate ? "new rooms are blocked." :"the server is closed."), connection.sid);
      return CloseReason.serverClosed;

    } else if (ClajConfig.maxRooms.get() > 0 && rooms.size >= ClajConfig.maxRooms.get()) {
      rejectRoomCreation(connection, CloseReason.serverFull);
      warn("Connection @ tried to create a room but the server is full.", connection.sid);
      return CloseReason.serverFull;

    } else if (version != ClajConfig.serverVersion) {
      boolean isGreater = version > ClajConfig.serverVersion;
      CloseReason reason = isGreater ? CloseReason.outdatedServer : CloseReason.outdatedClient;
      rejectRoomCreation(connection, reason);
      warn("Connection @ tried to create a room but has " + (isGreater ? "a too recent" : "an outdated") +
           " version. (was: @)", connection.sid, version);
      return reason;

    } else if (type != null && ClajConfig.typeBlacklist.contains(type)) {
      rejectRoomCreation(connection, CloseReason.blacklisted);
      warn("Connection @ tried to create a room but his implementation is blacklisted. (was: @)",
           connection.sid, type);
      return CloseReason.blacklisted;
    }

    ClajRoom room = connection.currentRoom();
    // Ignore if the connection is already in a room or hold one
    if (room != null) {
      denyAction(connection, room, MessageType.alreadyHosting);
      warn("Connection @ tried to create a room but is already hosting the room @.", connection.sid, room.sid);
      return CloseReason.error;

    // Must be checked last, as the room is counted if allowed
    } else if (!routines.getAddressRate(connection).allowCreate()) {
      rejectRoomCreation(connection, CloseReason.serverFull); // act as server full
      warn("Connection @ tried to create a room but reached the limit per IP.", connection.sid);
      return CloseReason.closed;
    }

    room = createRoom(connection, type);
    // In case of
    if (room.isClosed()) {
      rejectRoomCreation(connection, CloseReason.error);
      Log.err("Failed to create room @ with type @ requested by connection @.", room.sid, room.type, connection.sid);
      return CloseReason.error;
    } else {
      info("Room @ created by connection @ with type @.", room.sid, connection.sid, room.type);
      return null;
    }
  }

  /** @return whether the action was allowed or not. */
  public boolean onRoomClose(ClajConnection connection) {
    if (checkRoomHost(
        connection,
        MessageType.roomClosureDenied,
        "Connection @ tried to close the room @ but is not the host."
    )) return false;

    ClajRoom room = connection.currentRoom();
    closeRoom(room);
    info("Room @ closed by connection @ (the host).", room.sid, connection.sid);
    return true;
  }

  /** @return not {@code null} if the action was denied. */
  public RejectReason onRoomJoin(ClajConnection connection, boolean isRequest, long roomId, ClajType type,
                                 boolean withPassword, short password) {
    if (connection == null) return RejectReason.error;
    ClajRoom room = connection.currentRoom();

    // Disconnect from a potential another room.
    if (room != null) {
      // Ignore if it's the host of another room
      if (room.isHost(connection)) {
        denyAction(connection, room, MessageType.alreadyHosting);
        warn("Connection @ tried to join the room @ but is already hosting the room @.", connection.sid,
             Strings.longToBase64(roomId), room.sid);
        return RejectReason.error;
      }
      removeClient(connection);
    }

    room = getRoom(connection, roomId);

    // Check room accessibility
    if (isClosed() || blockJoin) {
      if (isRequest) rejectRoomJoin(connection, room, roomId, RejectReason.serverClosing);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join the room @ but "+
           (blockJoin ? "joins are blocked." : "the server is closed."), connection.sid,
           room == null ? Strings.longToBase64(roomId) : room.sid);
      return RejectReason.serverClosing;

    // Limit to avoid room searching
    } else if (!routines.getAddressRate(connection).allowJoin()) {
      // Act same way as not found
      if (isRequest) rejectRoomJoin(connection, room, roomId, RejectReason.roomNotFound);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join the room @ but was rate limited.", connection.sid,
           room == null ? Strings.longToBase64(roomId) : room.sid);
      return RejectReason.roomNotFound;

    } else if (room == null || room.isClosed()) {
      if (isRequest) rejectRoomJoin(connection, room, roomId, RejectReason.roomNotFound);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join a not found room. (id: @)", connection.sid, Strings.longToBase64(roomId));
      return RejectReason.roomNotFound;

    } else if (!room.allowsType(type)) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.incompatible);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join the room @ but has an incompatible type. (was: @, need: @)",
           connection.sid, room.sid, type, room.type);
      return RejectReason.incompatible;

    } else if (room.isProtected && !withPassword) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.passwordRequired);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join the room @ but a password is needed.", connection.sid, room.sid);
      return RejectReason.passwordRequired;

    } else if (room.isProtected && room.password != password) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.invalidPassword);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join the room @ but used the wrong password.", connection.sid, room.sid);
      return RejectReason.invalidPassword;

    } else if (room.isFull()) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.roomFull);
      else connection.close(DcReason.error);
      warn("Connection @ tried to join the room @ but it is full.", connection.sid, room.sid);
      return RejectReason.roomFull;

    // Stop here if it's a request
    } else if (isRequest) {
      acceptJoinRequest(connection, room);
      Log.debug("Connection @ validated its join request to the room @.", connection.sid, room.sid);
      return null;
    }

    if (addClient(room, connection)) {
      info("Connection @ joined the room @. (type: @)", connection.sid, room.sid, type);
      connection.handleQueue();
      return null;
    } else {
      connection.clearQueue();
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.error);
      else connection.close(DcReason.error);
      if (room.isClosed()) Log.err("Failed to add connection @ to room @. The room has been closed.",
                                   connection.sid, room.sid);
      return RejectReason.error;
    }
  }

  /** @return whether the action was allowed or not. */
  public boolean onRoomConfig(ClajConnection connection, boolean isPublic, boolean isProtected, short password,
                              boolean requestState, int maxClients) {
    if (checkRoomHost(
        connection,
        MessageType.configureDenied,
        "Connection @ tried to configure the room @ but is not the host."
    )) return false;

    ClajRoom room = connection.currentRoom();
    setRoomConfiguration(room, isPublic, isProtected, password, requestState, maxClients);
    info("Connection @ (the host) changed configuration of room @.", connection.sid, room.sid);
    return true;
  }

  /** @return whether the action was allowed or not. */
  public boolean onRoomState(ClajConnection connection, ByteBuffer state) {
    if (checkRoomHost(
        connection,
        MessageType.statingDenied,
        "Connection @ tried to set state of room @ but is not the host."
    )) return false;

    ClajRoom room = connection.currentRoom();
    int limit = ClajConfig.stateLimit.get();
    if (limit > 0 && !room.stateRate.allow(60000L, limit)) {
      warn("Connection @ (the host) tried to change state of room @ but was rate limited.", connection.sid,
           room.sid);
      return false;
    }

    setRoomState(room, state);
    info("Connection @ (the host) changed the state of room @.", connection.sid, room.sid);
    sendRoomState(room);
    sendRoomList(room.type);
    return true;
  }

  /** @return whether the action was allowed or not. */
  public boolean onInfoRequest(ClajConnection connection, long roomId) {
    if (connection == null) return false;
    else if (!routines.getAddressRate(connection).allowInfo()) {
      rejectRoomInfo(connection, null, true);
      warn("Connection @ tried to get state of room @ but was rate limited.", connection.sid,
           Strings.longToBase64(roomId));
      return false;
    }

    ClajRoom room = getRoom(connection, roomId);
    if (room == null) {
      rejectRoomInfo(connection, null, false);
      warn("Connection @ tried to get state of a not found room. (id: @)", connection.sid,
           Strings.longToBase64(roomId));
      return false;
    } else if (room.shouldRequestState() && room.isStateOutdated()) {
      //TODO: A non-answering host makes wait each times. Send the cached one and refresh in background instead?
      requestRoomState(connection, room);
      String msg = "Connection @ requested state of room @ but current one is " +
                   (room.requestingState ? "updating" : "outdated.");
      if (room.requestingState) Log.debug(msg, connection.sid, room.sid);
      else info(msg, connection.sid, room.sid);
      return false;
    } else {
      room.sendRoomState(connection);
      Log.debug("Connection @ requested state of room @.", connection.sid, room.sid);
      return true;
    }
  }

  /** @return whether the action was allowed or not. */
  public boolean onListRequest(ClajConnection connection, ClajType type) {
    if (connection == null) return false;
    else if (!routines.getAddressRate(connection).allowList()) {
      rejectRoomList(connection, type, true);
      if (type != null)
        warn("Connection @ tried to get room list of type @ but was rate limited.", connection.sid, type);
      return false;
    }

    switch (requestRoomList(connection, type)) {
      case 0 ->
        info("Connection @ requested room list of type @ but the current one is oudated.", connection.sid, type);
      case 1 ->
        Log.debug("Connection @ requested room list of type @.", connection.sid, type);
      case 2 ->
        Log.debug("Connection @ requested room list of type @ but the current one is not finished.", connection.sid, type);
      case 3 ->
        warn("Connection @ requested room list of type @ but the request limit is reached.", connection.sid, type);
      case 4 -> {
        if (type == null) break;
        warn("Connection @ tried to get room list of a not found type @.", connection.sid, type);
      }
    }
    return true;
  }

  /**
   * Will not notify the host about closing.
   * @return whether the action was allowed or not.
   */
  public boolean onConClose(ClajConnection connection, int conId, DcReason reason) {
    // Broadcast close
    if (conId == ClajRoom.CON_BROADCAST) {
      if (checkRoomHost(
          connection,
          MessageType.conClosureDenied,
          "Connection @ tried to close all connections of room @ but is not the host."
      )) return false;

      ClajRoom room = connection.currentRoom();
      clearRoom(room, reason, true);
      info("Connection @ (the host) closed all connections of room @.", connection.sid, room.sid);
      return true;
    }

    String tsid = AddressUtil.encodeId(conId);

    if (checkRoomHost(
        connection,
        MessageType.conClosureDenied,
        "Connection @ from room @ tried to close connection @ but is not the host.", tsid
    )) return false;

    ClajConnection target = connections.get(conId);
    ClajRoom room = connection.currentRoom();

    // Ignore when trying to close itself or one that not in the same room
    if (target == null || target == connection || room.isClosed() || !room.contains(target)) {
      denyAction(connection, room, MessageType.conClosureDenied);
      warn("Connection @ from room @ tried to close a " +
           (target == null ? "not found connection" : "connection of another room") + ". (id: @)",
           connection.sid, room.sid, tsid);
      return false;
    }

    removeClient(target, reason, true);
    if (!room.isHost(target) && room.isClosed())
      Log.err("Failed to remove connection @ from room @. The room has been closed", tsid, room.sid);
    else info("Connection @ (the host) from room @ closed connection @.", connection.sid, room.sid, tsid);
    return true;
  }

  public boolean onHostPacket(ClajConnection connection, ConnectionPayloadPacket packet) {
    if (connection == null) return false;
    ClajRoom room = connection.currentRoom();
    if (room == null) return false;
    room.received(connection, packet);
    return true;
  }

  public void onConPacket(ClajConnection connection, RawPacket packet) {
    if (connection == null) return;
    ClajRoom room = connection.currentRoom();
    if (room == null) connection.addQueue(packet);
    // Try to prevent a client from closing a room just by filling the host buffer.
    else if (!room.isHost(connection) && !room.canHostReceive(packet.data().remaining()))
      rejectFloodingClient(connection);
    else room.received(connection, packet);
  }

  // end region
  // region frames calculation

  // This is a reimplementation of MockGraphics
  private long lastFrame;
  private int frames, fps;

  protected void updateTime(int timeout) {
    long time = System.nanoTime();
    if (time - lastFrame >= 1000000000) {
      fps = frames;
      frames = 0;
      lastFrame = time;
    }
    frames++;
  }

  public int getFramesPerSecond() {
    return fps;
  }

  @Override
  public void update(int timeout) throws IOException {
    // Avoid io errors from being suppressed.
    try { super.update(timeout); }
    catch (IOException e) { throw new RuntimeException(e); }
    checkPendingConnections(Time.millis());
    updateTime(timeout);
  }

  /** Closes the connections that didn't complete their registration in time. */
  protected void checkPendingConnections(long time) {
    long timeout = ClajConfig.registerTimeout.get() * 1000L;
    if (timeout <= 0 || time - lastPendingCheck < timeout) return;
    lastPendingCheck = time;

    // Closing removes them from the map, so iterate over the last ids instead.
    lastPending.each(id -> {
      Connection c = pendingConnections.get(id);
      if (c != null) c.close(DcReason.timeout);
    });
    lastPending.clear();
    IntMap.Keys keys = pendingConnections.keys();
    while (keys.hasNext) lastPending.add(keys.next());
  }

  // end region
  // region hosting

  @Override
  public void init() {
    Events.on(ClajEvents.ServerLoadedEvent.class, _ -> host(port));
  }

  /** At this point it's too late to notify closure. */
  @Override
  public void dispose() {
    if (!stopping) {
      stopping = closed = true;
      Events.fire(new ServerStoppingEvent(false));
      clearAndStop();
    }
    try { super.dispose(); }
    catch (Exception _) {}
    Log.info("Server disposed.");
  }

  public void host() throws RuntimeException { host(port); }
  public void host(int port) throws RuntimeException {
    running = false;
    stop(false);
    try {
      String address = ClajConfig.bindAddress.get();
      if (address.isEmpty()) bind(port, port);
      else bind(new InetSocketAddress(address, port), new InetSocketAddress(address, port));
    }
    catch (BindException e) {
      throw new RuntimeException("Port " + port + " already in use! "
                               + "Make sure no other servers are running on the same port.");
    } catch (IOException e) { throw new UncheckedIOException(e); }

    Threads.thread("CLaJ Relay", () -> {
      try { run(); }
      catch (Throwable th) {
        if(!(th instanceof ClosedSelectorException)) {
          Threads.throwAppException(th);
          return;
        }
      }
      Log.info("Server stopped.");
    });
  }

  @Override
  public void run() {
    closed = stopping = false;
    running = true;
    try { super.run(); }
    finally { running = stopping = false; }
  }

  @Override
  public void stop() { stop(false); }
  public void stop(boolean notify) { stop(notify, null); }
  public void stop(Runnable stopped) { stop(true, stopped); }
  public void stop(boolean notify, Runnable stopped) {
    if (stopping) {
      clearAndStop();
      return;
    }
    stopping = closed = true;
    if (!notify) {
      clearAndStop(stopped);
      return;
    }
    notifyStop(() -> clearAndStop(stopped));
  }

  /** Will notify stopping and wait a little before running callback. (if configured for) */
  public void notifyStop(Runnable notified) {
    Events.fire(new ServerStoppingEvent(true));
    if (ClajConfig.warnClosing.get() && !rooms.isEmpty()) {
      float wait = ClajConfig.closeWait.get();
      Log.info("Notifying server closure to rooms... The server will exit in @s.", wait);
      rooms.eachValue(r -> r.message(MessageType.serverClosing));
      Timer.schedule(notified, wait);
    } else notified.run();
  }

  /** Runs the callback even if an event handler throws while stopping, then throws the first error. */
  protected void clearAndStop(Runnable stopped) {
    Throwable error = null;
    try { clearAndStop(); }
    catch (RuntimeException | Error e) { error = e; }
    try { if (stopped != null) stopped.run(); }
    catch (RuntimeException | Error e) {
      if (error == null) error = e;
      else if (e != error) error.addSuppressed(e);
    }
    rethrow(error);
  }

  protected void clearAndStop() {
    try { closeRooms(); }
    finally { // Even if an event handler throws, the server stops
      Seq<ClajConnection> streams = new Seq<>();
      for (ClajConnection con : connections.values()) {
        con.clearQueue();
        if (con.isStream()) streams.add(con);
      }
      connections.clear();
      // Arc's stop closes its connections. The stream relay's close here, and leave the registry once closed.
      streams.each(c -> {
        connections.put(c.id, c);
        c.close();
      });
      super.stop();
    }
  }

  /** This doesn't means the server is stopped. */
  public boolean isClosed() {
    return closed || stopping || !running;
  }

  public void setClose(boolean close) {
    closed = close;
  }

  public boolean isHosted() {
    return running;
  }

  public void closeRooms() { closeRooms(CloseReason.serverClosed); }
  /** Only closes rooms, connections that are not in a room are kept. */
  public void closeRooms(CloseReason reason) {
    routines.clearCaches((c, r) -> rejectRoomInfo(c, getRoom(r), false));
    // Each room is closed even if an event handler throws for another, the first error is thrown after.
    Throwable error = null;
    for (ClajRoom r : rooms.values().toSeq()) {
      try { r.close(reason); }
      catch (RuntimeException | Error e) { if (error == null) error = e; }
    }
    rooms.clear();
    types.clear();
    clientsInRooms = 0;
    rethrow(error);
  }

  /** Throws an error kept to finish a cleanup first, if any. */
  public static void rethrow(Throwable error) {
    if (error instanceof RuntimeException e) throw e;
    if (error instanceof Error e) throw e;
  }

  public void setRoomAfk(ClajRoom room, boolean isAfk) {
    if (isAfk) routines.scheludeRoomAfk(room, () -> {
      closeRoom(room, CloseReason.afk);
      info("Room @ closed due to a long period without anyone joining in.", room.sid);
    });
    else routines.cancelRoomAfk(room);
  }

  /** Creates a room with it's associated caches. If the room failed to create, it will immediately closed. */
  public ClajRoom createRoom(ClajConnection host, ClajType type) {
    ClajRoom room = newRoom(host, type);
    rooms.put(room.id, room);
    if (type != null) types.get(type, LongMap::new).put(room.id, room);
    clientsInRooms++;
    room.create();
    if (room.isClosed()) closeRoom(room);
    else setRoomAfk(room, true);
    return room;
  }

  /** Will not notify closure to room host. */
  public void closeRoom(ClajRoom room) { closeRoom(room, null); }
  public void closeRoom(ClajRoom room, CloseReason reason) { closeRoom(room, reason, true); }
  protected void closeRoom(ClajRoom room, CloseReason reason, boolean removeFromTypes) {
    if (room == null) return;
    clientsInRooms -= room.clients() + 1;
    rooms.remove(room.id);

    boolean removeList = false;
    if (removeFromTypes && room.type != null) {
      LongMap<ClajRoom> r = types.get(room.type);
      if (r != null) {
        r.remove(room.id);
        if (r.isEmpty()) {
          types.remove(room.type);
          removeList = true;
        }
      }
    }

    ClajRoutines.AddressRater rater = routines.rates.get(room.host.address);
    if (rater != null) rater.removeRoom();
    setRoomAfk(room, false);
    Seq<ClajConnection> cons = routines.getPendingRoomRequestsForSend(room);
    if (cons != null) cons.each(c -> rejectRoomInfo(c, room, false));

    if (removeFromTypes) routines.clearRoomCache(room, removeList);
    else routines.cancelRoomInfoTask(room);

    if (reason == null) room.closeQuietly();
    else room.close(reason);
  }

  public int closeRoomList(ClajType type) { return closeRoomList(type, null); }
  /** @return the number of room that has been closed. {@code -1} if no room have the type. */
  public int closeRoomList(ClajType type, CloseReason reason) {
    if (type == null) return -1;
    LongMap<ClajRoom> rooms = types.remove(type);
    if (rooms == null) return -1;
    int number = rooms.size;
    // Each room is closed even if an event handler throws for another, the first error is thrown after.
    Throwable error = null;
    for (ClajRoom r : rooms.values().toSeq()) {
      try { closeRoom(r, reason, false); }
      catch (RuntimeException | Error e) { if (error == null) error = e; }
    }
    routines.clearRoomListCache(type);
    rethrow(error);
    return number;
  }

  /** @return whether the client has been added to the room or not. */
  public boolean addClient(ClajRoom room, ClajConnection con) {
    if (con == null || room == null) return false;
    int clients = room.clients(); // because all clients will be kicked before cleaning cache
    if (room.connected(con)) {
      clientsInRooms++;
      setRoomAfk(room, false);
      return true;
    } else if (room.isClosed()) {
      clientsInRooms -= clients;
      closeRoom(room);
    }
    return false;
  }

  public boolean removeClient(ClajConnection con) { return removeClient(con, DcReason.closed); }
  public boolean removeClient(ClajConnection con, DcReason reason) { return removeClient(con, reason, false); }
  /** @return whether client was the host. If so the room will be closed. */
  public boolean removeClient(ClajConnection con, DcReason reason, boolean quiet) {
    if (con == null) return false;
    routines.clearClientCache(con);
    ClajRoom room = con.currentRoom();
    if (room == null) return false;
    boolean wasHost = con.isRoomHost();
    int clients = room.clients(); // because all clients can be kicked before cleaning cache

    try {
      if (quiet) room.disconnectedQuietly(con, reason);
      else room.disconnected(con, reason);
    } finally { // Even if an event handler throws
      // Close the room if it was the host
      if (!wasHost && !room.isClosed()) {
        clientsInRooms--;
        setRoomAfk(room, true);
      } else {
        clientsInRooms -= clients;
        closeRoom(room);
      }
    }
    return wasHost;
  }

  /** Disconnect all clients of the room. */
  public void clearRoom(ClajRoom room, DcReason reason, boolean quiet) {
    if (room == null) return;
    room.clients.each(routines::clearClientCache);
    clientsInRooms -= room.clients();
    try { room.disconnectAllClients(reason, !quiet); }
    finally { setRoomAfk(room, true); } // Even if an event handler throws
  }

  public void setRoomConfiguration(ClajRoom room, boolean isPublic, boolean isProtected, short password,
                                   boolean requestState, int maxClients) {
    room.setConfiguration(isPublic, isProtected, password, requestState, maxClients);
    routines.updateRoom(room, false);
  }

  public void setRoomState(ClajRoom room, ByteBuffer state) {
    room.setState(state);
    routines.updateRoom(room, true);
  }

  /** Requests a room state (if not already) and adds the connection to the pending requests cache. */
  public boolean requestRoomState(ClajConnection con, ClajRoom room) {
    Seq<ClajConnection> cons = routines.getPendingRoomRequests(room);
    int limit = ClajConfig.infoRequestLimit.get();
    if (limit > 0 && cons.size >= limit) {
      rejectRoomInfo(con, room, true);
      return false;
    }
    cons.add(con);
    return routines.requestRoomState(room, this::sendRoomState);
  }

  /**
   * Send room state to connections that awaiting it.
   * @return whether any connections are waiting for the state.
   */
  public boolean sendRoomState(ClajRoom room) {
    Seq<ClajConnection> cons = routines.getPendingRoomRequestsForSend(room);
    if (cons == null) return false;
    Log.debug("Sending state of room @ to @ pending request" + (cons.size > 1 ? "s..." : "..."),
              room.sid, cons.size);
    routines.cancelRoomInfoTask(room);
    cons.each(room::sendRoomState);
    return true;
  }

  /**
   * Requests a room list (if not already) and adds the connection to the pending requests cache.
   * @return the state value. (0: refreshing, 1: up to date, 2: updating, 3: pending request limit, 4: type not found)
   */
  public int requestRoomList(ClajConnection con, ClajType type) {
    return routines.requestRoomList(con, type, type == null ? null : types.get(type), r -> rejectRoomList(con, type, r));
  }

  public boolean sendRoomList(ClajType type) { return sendRoomList(type, false); }
  public boolean sendRoomList(ClajType type, boolean force) {
    return routines.sendRoomList(type, force);
  }

  public boolean refreshRoomList(ClajType type) { return refreshRoomList(type, false); }
  public boolean refreshRoomList(ClajType type, boolean force) {
    return routines.refreshRoomList(type, force, types.get(type, (LongMap<ClajRoom>)null));
  }

  public void refreshRoomLists() {
    types.each(routines::refreshRoomList);
  }


  public void denyAction(ClajConnection con, ClajRoom room, MessageType type) {
    if (con == null || room == null) return;
    if (room.isHost(con)) room.message(type);
    Events.fire(new ActionDeniedEvent(con, room, type));
  }

  protected boolean checkRoomHost(ClajConnection con, MessageType errType, String errMsg) {
    return checkRoomHost(con, errType, errMsg, null);
  }
  protected boolean checkRoomHost(ClajConnection con, MessageType errType, String errMsg, Object extra) {
    if (con == null) return true;
    ClajRoom room = con.currentRoom();
    if (room == null) return true;
    if (room.isHost(con)) return false;
    denyAction(con, room, errType);
    if (extra == null) warn(errMsg, con.sid, room.sid);
    else warn(errMsg, con.sid, room.sid, extra);
    return true;
  }

  /**
   * Data rate protection, see {@link ClajConfig#clientRate} and {@link ClajConfig#hostRate}.
   * @return whether the packet is allowed or not. If not, the client will be kicked or the room closed.
   */
  public boolean checkRateLimit(ClajConnection con, long bytes) {
    if (con == null || !con.isConnected()) return true;
    ClajRoom room = con.currentRoom();
    boolean isHost = room != null && room.isHost(con);
    long perSecond = rateOf(room, isHost);
    if (perSecond > 0) con.rate.refill(System.nanoTime(), perSecond);
    boolean isRated = perSecond > 0 && !con.rate.take(bytes);
    if (isRated) {
      if (isHost) rejectRateLimitedHost(room);
      else rejectRateLimitedClient(con);
    }
    return !isRated;
  }

  /** @return the data rate a connection may send, in bytes per second, {@code 0} for no limit. */
  public static long rateOf(ClajRoom room, boolean isHost) {
    if (!isHost) return ByteRate.bytesPerSecond(ClajConfig.clientRate.get());
    return ByteRate.bytesPerSecond(ClajConfig.hostRate.get()) * (room.clients() + 1);
  }

  // end region
  // region packet sending

  public void rejectObsoleteClient(ClajConnection connection) {
    if (connection == null) return;
    if (ClajConfig.warnDeprecated.get()) {
      // Mmmm yea, mindustry related...
      connection.send("[scarlet][[CLaJ Server]:[] Your CLaJ version is obsolete! "
                    + "Please upgrade it by installing the 'claj' mod, in the mod browser.");
      warn("Connection @ tried to create a room but has an incompatible version.", connection.sid);
      Events.fire(new RoomCreationRejectedEvent(connection, CloseReason.obsoleteClient));
    }
    connection.close(DcReason.error);
  }

  public void rejectRateLimitedClient(ClajConnection connection) {
    kickClient(connection, "going over its data rate");
  }

  /** Kicks a client whose packet would overflow the room host connection. */
  public void rejectFloodingClient(ClajConnection connection) {
    kickClient(connection, "flooding the room host");
  }

  protected void kickClient(ClajConnection connection, String why) {
    if (connection == null) return;
    ClajRoom room = connection.currentRoom();
    if (room != null) {
      // TODO: ai tell me this can kick the host in extreme case,
      //       when checking for a client sending too big packets.
      room.message(MessageType.packetSpamming);
      removeClient(connection);
    }
    warn("Connection @ (@) disconnected for " + why + ".", connection.sid, connection.saddress);
    Events.fire(new ClientKickedEvent(connection));
    connection.close();
  }

  public void rejectRateLimitedHost(ClajRoom room) {
    if (room == null) return;
    closeRoom(room, CloseReason.spam);
    warn("Room @ closed for going over its data rate.", room.sid);
    Events.fire(new HostKickedEvent(room));
  }

  /** A stream relay connection is not closed: it may send another request. */
  public void rejectRoomCreation(ClajConnection connection, CloseReason reason) {
    if (connection == null) return;
    ClajRoom.sendRoomClosed(connection, reason);
    Events.fire(new RoomCreationRejectedEvent(connection, reason));
    if (!connection.isStream()) connection.close(); // keep that as compatibility for old versions
  }

  public void rejectRoomJoin(ClajConnection connection, ClajRoom room, RejectReason reason) {
    rejectRoomJoin(connection, room, room.id, reason);
  }
  /** A join refused before its room is looked up, e.g. by the stream relay. */
  public void rejectRoomJoin(ClajConnection connection, long roomId, RejectReason reason) {
    rejectRoomJoin(connection, null, roomId, reason);
  }
  /** A stream relay connection is not closed: it may send another request. */
  protected void rejectRoomJoin(ClajConnection connection, ClajRoom room, long roomId, RejectReason reason) {
    if (connection == null) return;
    ClajRoom.sendConnectionRejected(connection, room == null ? roomId : room.id, reason);
    Events.fire(new ConnectionJoinRejectedEvent(connection, room, reason));
    if (!connection.isStream()) connection.close(); //close?
  }

  public void acceptJoinRequest(ClajConnection connection, ClajRoom room) {
    if (connection == null) return;
    ClajRoom.sendConnectionAccepted(connection, room);
    Events.fire(new ConnectionPreJoinEvent(connection, room));
  }

  public void rejectRoomInfo(ClajConnection connection, ClajRoom room, boolean rateLimited) {
    if (connection == null) return;
    ClajRoom.sendRoomInfoRejected(connection);
    Events.fire(new RoomInfoRejectedEvent(connection, room, rateLimited));
    //connection.close(); // can be annoying
  }

  public void rejectRoomList(ClajConnection connection, ClajType type, boolean rateLimited) {
    if (connection == null) return;
    connection.send(emptyList);
    Events.fire(new RoomListRejectedEvent(connection, type, rateLimited));
    //connection.close(); // closing can be handled as rejected
  }

  // end region
  // region getters

  public int clientsInRooms() {
    return clientsInRooms;
  }

  public long newRoomId() {
    long id;
    /* re-roll if 0 because it's used to specify an uncreated room. */
    do { id = random.nextLong(); }
    while (id == ClajRoom.UNCREATED_ROOM || rooms.containsKey(id));
    return id;
  }

  public ClajRoom newRoom(ClajConnection host, ClajType type) {
    if (host.connection instanceof StreamConnection con) return con.relay.newRoom(newRoomId(), host, type);
    return new ClajRoom(newRoomId(), host, type);
  }

  public ClajRoom getRoom(long roomId) {
    return rooms.get(roomId);
  }

  /** @return the room, if the connection can join it: rooms of the other transport are not found. */
  public ClajRoom getRoom(ClajConnection connection, long roomId) {
    ClajRoom room = rooms.get(roomId);
    return room == null || room.isStream() != connection.isStream() ? null : room;
  }

  /** Try to find a room using the base64 encoded id. */
  public ClajRoom getRoom(String encodedRoomId) {
    if (encodedRoomId == null) return null;
    try { return getRoom(Strings.base64ToLong(encodedRoomId)); }
    catch (Exception _) { return null; }
  }

  public static ClajConnection toClajCon(Connection con) {
    return con != null && con.getArbitraryData() instanceof ClajConnection ccon ? ccon : null;
  }

  // end region
}
