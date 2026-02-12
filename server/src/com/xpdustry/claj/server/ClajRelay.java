/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2025  Xpdustry
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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.net.*;
import arc.struct.*;
import arc.util.*;

import com.xpdustry.claj.common.ClajNet;
import com.xpdustry.claj.common.ClajPackets.*;
import com.xpdustry.claj.common.net.NetListenerFilter;
import com.xpdustry.claj.common.net.ServerReceiver;
import com.xpdustry.claj.common.net.stream.*;
import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.*;
import com.xpdustry.claj.common.util.AddressUtil;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.server.ClajEvents.*;
import com.xpdustry.claj.server.util.NetworkSpeed;


public class ClajRelay extends Server implements ApplicationListener {
  protected boolean closed;
  /** Server packet receiver. */
  protected final ServerReceiver receiver;
  /** Read/Write speed. */
  public final NetworkSpeed networkSpeed;
  /** List of created rooms. */
  public final LongMap<ClajRoom> rooms = new LongMap<>();
  /** To easily get the room of a connection. */
  public final IntMap<ClajRoom> conToRoom = new IntMap<>();
  /** Rooms sorted by type. */
  public final ObjectMap<ClajType, LongMap<ClajRoom>> types = new ObjectMap<>(8);

  // Caches
  /** List of join request rate by ip. */
  protected final ObjectMap<String, AddressRater> rates = new ObjectMap<>(32);
  /**
   * Keeps a cache of packets received from connections that are not yet in a room. (queue of 3)<br>
   * Sometimes the join packet comes after other packets, and can lead to a client-side error/timeout.
   */
  private final IntMap<RawPacket[]> packetQueue = new IntMap<>();
  /** Size of the packet queue. */
  private final int packetQueueSize = 3;
  /** As server version will not change at runtime, cache the serialized packet to avoid re-serialization. */
  private ByteBuffer versionBuff;
  //TODO: make the room host calculate the idle instead of the server, this will save bandwidth.
  /** Keeps a cache of already notified idling connection, to avoid packet spamming. */
  private final IntSet notifiedIdle = new IntSet();
  /** List of client who requested the state of a room that was outdated.*/
  protected final LongMap<Seq<ClajConnection>> pendingInfoRequests = new LongMap<>(16);
  /** Use cleaner task instead of storing the {@link #pendingInfoRequests} invert, to avoid having to many caches. */
  protected final LongMap<Timer.Task> pendingInfoTasks = new LongMap<>(16);
  /** Cache for room list requests. */
  protected final ObjectMap<ClajType, CachedRoomList> listCache = new ObjectMap<>(8);
  /** Empty room list to send to client requesting no type or a not found one. */
  protected final RoomListPacket emptyList = new RoomListPacket();

  public ClajRelay() { this(null); }
  public ClajRelay(NetworkSpeed speedCalculator) {
    super(32768, 32768, new ClajServerSerializer(speedCalculator));
    networkSpeed = speedCalculator;
    receiver = new ServerReceiver(this, Core.app::post);

    setDiscoveryHandler((c, r) -> {
      if (versionBuff == null)
        versionBuff = ByteBuffer.allocate(5).put(ClajNet.id).putInt(ClajVars.version.majorVersion);
      r.respond((ByteBuffer)versionBuff.rewind());
    });

    receiver.setFilter(new NetListenerFilter() {
      public boolean connected(Connection connection) { return isConnectAllowed(connection); }
      public boolean disconnected(Connection connection, DcReason reason) { return isDisconnectAllowed(connection, reason); }
      public boolean received(Connection connection, Object object) { return isReceiveAllowed(connection, object); }
      public boolean idle(Connection connection) { return isIdleAllowed(connection); }
    });

    receiver.handle(Connect.class, this::onConnect);
    receiver.handle(Disconnect.class, (c, p) -> onDisconnect(c, p.reason));
    receiver.handle(Idle.class, this::onIdle);

    receiver.handle(RoomCreationRequestPacket.class, (c, p) -> onRoomCreate(toClajCon(c), p.version, p.type));
    receiver.handle(RoomClosureRequestPacket.class, (c, p) -> onRoomClose(toClajCon(c), find(c)));
    receiver.handle(RoomJoinPacket.class, (c, p) ->
      onRoomJoin(toClajCon(c), false, p.roomId, p.type, p.withPassword, p.password));
    receiver.handle(RoomJoinRequestPacket.class, (c, p) ->
      onRoomJoin(toClajCon(c), true, p.roomId, p.type, p.withPassword, p.password));
    receiver.handle(RoomConfigPacket.class, (c, p) ->
      onRoomConfig(toClajCon(c), find(c), p.isPublic, p.isProtected, p.password, p.requestState));
    receiver.handle(RoomStatePacket.class, (c, p) -> onRoomState(toClajCon(c), find(c), p.state));
    receiver.handle(RoomInfoRequestPacket.class, (c, p) -> onInfoRequest(toClajCon(c), p.roomId));
    receiver.handle(RoomListRequestPacket.class, (c, p) -> onListRequest(toClajCon(c), p.type));

    receiver.handle(ConnectionClosedPacket.class, (c, p) -> onConClose(toClajCon(c), find(c), p.conID, p.reason));
    //TODO: keep these two to the server thread for optimization?
    receiver.handle(ConnectionPacketWrapPacket.class, (c, p) -> onHostPacket(c, find(c), p));
    receiver.handle(RawPacket.class, (c, p) -> onConPacket(c, find(c), p));
  }

  // region events

  /** Will also prepare the connection if valid. */
  public boolean isConnectAllowed(Connection connection) {
    if (connection == null) return false;
    String id = AddressUtil.encodeId(connection);
    String ip = AddressUtil.get(connection);
    connection.setName("Connection " + id); // fix id format in stacktraces

    if (isClosed() || ClajConfig.blacklist.contains(ip)) {
      connection.close(DcReason.closed);
      Log.warn("Connection @ (@) rejected " +
               (isClosed() ? "because of a closed server." : "for a blacklisted address."), id, ip);
      return false;
    }

    Log.debug("Connection @ (@) received.", id, ip);
    ClajConnection con = new ClajConnection(connection, ip, id);
    connection.setArbitraryData(con);
    return true;
  }

  /** Weird name =/ */
  public boolean isDisconnectAllowed(Connection connection, DcReason reason) {
    if (connection == null) return false;
    ClajConnection con = toClajCon(connection);
    String id = con != null ? con.sid : AddressUtil.encodeId(connection);
    String ip = con != null ? con.address : AddressUtil.get(connection);

    Log.debug("Connection @ (@) lost: @.", id, ip, reason);
    notifiedIdle.remove(connection.getID());
    StreamReceiver.reset(connection); // in case of

    // Avoid searching for a room if it was an invalid connection or just a ping
    return con != null;
  }

  public boolean isReceiveAllowed(Connection connection, Object object) {
    ClajConnection con = toClajCon(connection);
    if (con == null) return false;

    // Compatibility with the xzxADIxzx's version
    if (object instanceof String) {
      rejectObsoleteClient(con);
      return false;
    }

    notifiedIdle.remove(con.id);
    ClajRoom room = find(con); //NOTE: Not thread-safe but shouldn't be a problem
    return checkRateLimit(con, room);
  }

  /** Ignores if the connection idle state was already notified to the room host. */
  public boolean isIdleAllowed(Connection connection) {
    ClajConnection con = toClajCon(connection);
    return con != null && notifiedIdle.add(con.id);
  }

  public boolean idleNotified(ClajConnection connection) {
    return notifiedIdle.contains(connection.id);
  }

  public void onConnect(Connection connection) {
    ClajConnection con = toClajCon(connection);
    if (con != null) Events.fire(new ClientConnectedEvent(con));
  }

  public void onDisconnect(Connection connection, DcReason reason) {
    if (connection == null) return;
    ClajRoom room = find(connection);
    ClajConnection con = toClajCon(connection);
    String sid = con != null ? con.sid : AddressUtil.encodeId(connection);

    if (con != null) Events.fire(new ClientDisonnectedEvent(con, reason));
    if (removeClient(room, con, connection, reason))
      Log.info("Room @ closed because connection @ (the host) has disconnected.", room.sid, sid);
    else if (room != null) Log.info("Connection @ left the room @.", sid, room.sid);
  }

  public void onIdle(Connection connection) {
    if (connection == null) return;
    ClajRoom room = find(connection);
    if (room != null) room.idle(connection);
    // No event for that, this is received to many times
  }

  public void onRoomCreate(ClajConnection connection, int version, ClajType type) {
    if (connection == null) return;
    // Ignore room creation requests when the server is closing
    if (isClosed()) {
      rejectRoomCreation(connection, CloseReason.serverClosed);
      Log.warn("Connection @ tried to create a room but the server is closed.", connection.sid);
      return;
    } else if (version != ClajVars.version.majorVersion) {
      boolean isGreater = version > ClajVars.version.majorVersion;
      rejectRoomCreation(connection, isGreater ? CloseReason.outdatedServer : CloseReason.outdatedClient);
      Log.warn("Connection @ tried to create a room but has " + (isGreater ? "a too recent" : "an outdated") +
               " version. (was: @)", connection.sid, version);
      return;
    } else if (type != null && ClajConfig.blacklistedTypes.contains(type)) {
      rejectRoomCreation(connection, CloseReason.blacklisted);
      Log.warn("Connection @ tried to create a room but his implementation is blacklisted. (was: @)", connection.sid,
               type);
      return;
    }

    ClajRoom room = find(connection);
    // Ignore if the connection is already in a room or hold one
    if (room != null) {
      denyAction(connection, room, MessageType.alreadyHosting);
      Log.warn("Connection @ tried to create a room but is already hosting the room @.", connection.sid, room.sid);
      return;
    }

    room = createRoom(connection, type);
    Log.info("Room @ created by connection @.", room.sid, connection.sid);
  }

  public void onRoomClose(ClajConnection connection, ClajRoom room) {
    if (checkRoomHost(connection, room, MessageType.roomClosureDenied,
                      "Connection @ tried to close the room @ but is not the host.")) return;
    closeRoom(room);
    Log.info("Room @ closed by connection @ (the host).", room.sid, connection.sid);
  }

  public void onRoomJoin(ClajConnection connection, boolean isRequest, long roomId, ClajType type,
                         boolean withPassword, short password) {
    if (connection == null) return;
    ClajRoom room = find(connection);

    // Disconnect from a potential another room.
    if (room != null) {
      // Ignore if it's the host of another room
      if (room.isHost(connection)) {
        denyAction(connection, room, MessageType.alreadyHosting);
        Log.warn("Connection @ tried to join the room @ but is already hosting the room @.", connection.sid,
                 Strings.longToBase64(roomId), room.sid);
        return;
      }
      room.disconnected(connection, DcReason.closed);
    }

    room = get(roomId);

    // Check room accessibility
    if (isClosed()) {
      if (isRequest) rejectRoomJoin(connection, room, roomId, RejectReason.serverClosing);
      else connection.close(DcReason.error);
      Log.warn("Connection @ tried to join the room @ but the server is closed.", connection.sid,
               room == null ? Strings.longToBase64(roomId) : room.sid);
      return;
    } else if (room == null) {
      if (isRequest) rejectRoomJoin(connection, room, roomId, RejectReason.roomNotFound);
      else connection.close(DcReason.error);
      Log.warn("Connection @ tried to join a not found room. (id: @)", connection.sid,
               Strings.longToBase64(roomId));
      return;
    // Limit to avoid room searching
    } else if (!getAddressRate(connection).allowJoin()) {
      // Act same way as not found
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.roomNotFound);
      else connection.close(DcReason.error);
      Log.warn("Connection @ tried to join the room @ but was rate limited.", connection.sid, room.sid);
      return;
    } else if (room.type != null && !room.type.equals(type) && !(type == null && ClajConfig.acceptNoType)) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.incompatible);
      else connection.close(DcReason.error);
      Log.warn("Connection @ tried to join the room @ but has an incompatible type. (was: @, need: @)",
               connection.sid, room.sid, type, room.type);
      return;
    } else if (room.isProtected && !withPassword) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.passwordRequired);
      else connection.close(DcReason.error);
      Log.warn("Connection @ tried to join the room @ but a password is needed.", connection.sid, room.sid);
      return;
    } else if (room.isProtected && room.password != password) {
      if (isRequest) rejectRoomJoin(connection, room, RejectReason.invalidPassword);
      else connection.close(DcReason.error);
      Log.warn("Connection @ tried to join the room @ but used the wrong password.", connection.sid, room.sid);
      return;

    // Stop here if it's a request
    } else if (isRequest) {
      acceptJoinRequest(connection, room);
      Log.debug("Connection @ validated its join request to the room @.", connection.sid, room.sid);
      return;
    }

    addClient(room, connection);
    Log.info("Connection @ joined the room @. (type: @)", connection.sid, room.sid, type);
    handleQueue(connection, room);
  }

  public void onRoomConfig(ClajConnection connection, ClajRoom room, boolean isPublic, boolean isProtected,
                           short password, boolean requestState) {
    if (checkRoomHost(connection, room, MessageType.configureDenied,
                      "Connection @ tried to confgure the room @ but is not the host.")) return;
    setRoomConfiguration(room, isPublic, isProtected, password, requestState);
    Log.info("Connection @ (the host) changed configuration of room @.", connection.sid, room.sid);
  }

  public void onRoomState(ClajConnection connection, ClajRoom room, ByteBuffer state) {
    if (checkRoomHost(connection, room, MessageType.statingDenied,
                      "Connection @ tried to set state of room @ but is not the host.")) return;
    setRoomState(room, state);
    Log.info("Connection @ (the host) changed the state of room @.", connection.sid, room.sid);
    sendRoomState(room);
    sendRoomList(room.type);
  }

  public void onInfoRequest(ClajConnection connection, long roomId) {
    if (connection == null) return;
    else if (!getAddressRate(connection).allowInfo()) {
      rejectRoomInfo(connection);
      Log.warn("Connection @ tried to get state of room @ but was rate limited.", connection.sid,
               Strings.longToBase64(roomId));
      return;
    }

    ClajRoom room = get(roomId);
    if (room == null) {
      rejectRoomInfo(connection);
      Log.warn("Connection @ tried to get state of a not found room. (id: @)", connection.sid,
               Strings.longToBase64(roomId));
    } else if (room.shouldRequestState() && room.isStateOutdated()) {
      requestRoomState(connection, room);
      Log.info("Connection @ requested state of room @ but current one is " +
               (room.requestingState ? "in progress" : "outdated."), connection.sid, room.sid);
    } else {
      room.sendRoomState(connection);
      Log.info("Connection @ requested state of room @.", connection.sid, room.sid); //TODO: maybe debug?
    }
  }

  public void onListRequest(ClajConnection connection, ClajType type) {
    if (connection == null) return;
    else if (!getAddressRate(connection).allowList()) {
      connection.send(emptyList);
      if (type != null)
        Log.warn("Connection @ tried to get room list of type @ but was rate limited.", connection.sid, type);
      return;
    }

    //TODO: maybe debug?
    switch (requestRoomList(connection, type)) {
      case 0 ->
        Log.info("Connection @ requested room list of type @ but current one is oudated.", connection.sid, type);
      case 1 ->
        Log.info("Connection @ requested room list of type @.", connection.sid, type);
      case 2 ->
        Log.info("Connection @ requested room list of type @ but current one is not finished.", connection.sid, type);
      case 3 -> {
        if (type == null) break;
        Log.warn("Connection @ tried to get room list of a not found type @.", connection.sid, type);
      }
    }
  }

  /** Will not notify the host about closing. */
  public void onConClose(ClajConnection connection, ClajRoom room, int conId, DcReason reason) {
    String tsid = AddressUtil.encodeId(conId);

    if (checkRoomHost(connection, room, MessageType.conClosureDenied,
                      "Connection @ from room @ tried to close connection @ but is not the host.", tsid)) return;
    Connection target = Structs.find(getConnections(), cc -> cc.getID() == conId);

    // Ignore when trying to close itself or one that not in the same room
    if (target == null || target == connection.connection || !room.contains(target)) {
      denyAction(connection, room, MessageType.conClosureDenied);
      Log.warn("Connection @ from room @ tried to close a " +
               (target == null ? "not found connection" : "connection of another room") + ". (id: @)",
               connection.sid, room.sid, tsid);
      return;
    }

    Log.info("Connection @ from room @ closed connection @.", connection.sid, room.sid, tsid);
    room.disconnectedQuietly(target, reason);
    target.close(reason);
  }

  public void onHostPacket(Connection connection, ClajRoom room, ConnectionPacketWrapPacket packet) {
    if (room == null) return;
    if (room.isHost(connection)) notifiedIdle.remove(packet.conID); // not thread-safe but i don't care
    room.received(connection, packet);
  }

  public void onConPacket(Connection connection, ClajRoom room, RawPacket packet) {
    if (room != null) room.received(connection, packet);
    else if (connection != null) addQueue(connection, packet);
  }

  // end region
  // region hosting

  @Override
  public void init() {
    Events.on(ClajEvents.ServerLoadedEvent.class, e -> host(ClajVars.port));
  }

  /** At this point it's too late to notify closure. */
  @Override
  public void dispose() {
    if (!closed) {
      closed = true;
      Events.fire(new ServerStoppingEvent(false));
      clearAndStop();
    }
    try { super.dispose(); }
    catch (Exception ignored) {}
  }

  public void host(int port) throws RuntimeException {
    try { bind(port, port); }
    catch (BindException e) { throw new RuntimeException(
      "Port " + port + " already in use! Make sure no other servers are running on the same port."); }
    catch (IOException e) { throw new UncheckedIOException(e); }

    Threads.daemon("CLaJ Relay", () -> {
      try { run(); }
      catch (Throwable th) {
        if(!(th instanceof ClosedSelectorException)) {
          Threads.throwAppException(th);
          return;
        }
      }
      Log.info("Server closed.");
    });
  }

  @Override
  public void run() {
    closed = false;
    super.run();
  }

  @Override
  public void stop() { stop(null); }
  /** Call twice to force stop. */
  public void stop(Runnable stopped) {
    if (closed) {
      clearAndStop();
      return;
    }
    closed = true;
    notifyStop(() -> {
      clearAndStop();
      if (stopped != null) stopped.run();
    });
  }

  /** Will notify stopping and wait a little before running callback. (if configured for) */
  public void notifyStop(Runnable notified) {
    Events.fire(new ServerStoppingEvent(true));
    if (ClajConfig.warnClosing && !rooms.isEmpty()) {
      Log.info("Notifying server closure to rooms... The server will exit in @s.", ClajConfig.closeWait);
      rooms.eachValue(r -> r.message(MessageType.serverClosing));
      Timer.schedule(notified, ClajConfig.closeWait);
    } else notified.run();
  }

  /** Internal */
  public void clearAndStop() {
    closeRooms();
    clearCache();
    super.stop();
  }

  public boolean isClosed() {
    return closed;
  }

  public void closeRooms() {
    rooms.eachValue(r -> r.close(CloseReason.serverClosed));
    rooms.clear();
    conToRoom.clear();
    types.clear();
  }

  public void clearCache() {
    packetQueue.clear();
    pendingInfoRequests.eachValue(c -> c.each(this::rejectRoomInfo));
    pendingInfoRequests.clear();
    pendingInfoTasks.eachValue(Timer.Task::cancel);
    pendingInfoTasks.clear();
    listCache.each((t, c) -> c.send());
    listCache.clear();
  }

  // The following methods are here to keep cache consistency.

  /** Creates a room with it's associated caches. */
  public ClajRoom createRoom(ClajConnection host, ClajType type) {
    ClajRoom room = newRoom(host, type);
    rooms.put(room.id, room);
    conToRoom.put(host.id, room);
    if (type != null) types.get(type, LongMap::new).put(room.id, room);
    room.create();
    return room;
  }

  /** Close the room and removes the associated caches. */
  public void closeRoom(ClajRoom room) {
    for (ClajConnection c : room.clients.values()) {
      removeQueue(c);
      conToRoom.remove(c.id);
    }
    rooms.remove(room.id);
    Seq<ClajConnection> cons = pendingInfoRequests.remove(room.id);
    if (cons != null) cons.each(this::rejectRoomInfo);
    Timer.Task task = pendingInfoTasks.remove(room.id);
    if (task != null) task.cancel();

    if (room.type != null) {
      LongMap<ClajRoom> r = types.get(room.type);
      boolean removeCache = false;
      if (r != null) {
        r.remove(room.id);
        if (r.isEmpty()) {
          types.remove(room.type);
          removeCache = true;
        }
      }
      CachedRoomList c = listCache.get(room.type);
      if (c != null) {
        c.remove(room.id);
        if (removeCache) {
          c.send(); // in case of pending requests
          listCache.remove(room.type);
        }
      }
    }

    room.close();
  }

  public void addClient(ClajRoom room, ClajConnection con) {
    conToRoom.put(con.id, room);
    room.connected(con);
  }

  /** @return whether client was the host. If so the room will be closed. */
  public boolean removeClient(ClajRoom room, ClajConnection con, DcReason reason) {
    return removeClient(room, con, con.connection, reason);
  }

  /** @return whether client was the host. If so the room will be closed. */
  protected boolean removeClient(ClajRoom room, ClajConnection con, Connection connection, DcReason reason) {
    removeQueue(connection);
    conToRoom.remove(connection.getID());

    if (room != null) {
      room.disconnected(connection, reason);
      // Close the room if it was the host
      if (room.isHost(connection)) {
        closeRoom(room);
        return true;
      }
    }
    return false;
  }

  public void setRoomConfiguration(ClajRoom room, boolean isPublic, boolean isProtected, short password,
                                   boolean requestState) {
    room.setConfiguration(isPublic, isProtected, password, requestState);

    CachedRoomList cache = listCache.get(room.type);
    if (cache != null) cache.set(room, false);
  }

  public void setRoomState(ClajRoom room, ByteBuffer state) {
    room.setState(state);

    CachedRoomList cache = listCache.get(room.type);
    if (cache != null) cache.set(room, true);
  }

  /** Requests a room state (if not already) and adds the connection to the pending requests cache. */
  public void requestRoomState(ClajConnection con, ClajRoom room) {
    Seq<ClajConnection> cons = pendingInfoRequests.get(room.id);
    if (cons == null) pendingInfoRequests.put(room.id, cons = new Seq<>(false, 4));
    cons.add(con);

    if (!room.requestState()) return;
    Timer.Task old = pendingInfoTasks.put(room.id, Timer.schedule(() -> {
      pendingInfoTasks.remove(room.id);
      sendRoomState(room);
    }, ClajConfig.stateTimeout / 1000));
    if (old != null) old.cancel(); // In case of
  }

  /**
   * Send room state to connections that awaiting it.
   * @return whether any connections are waiting for the state.
   */
  public boolean sendRoomState(ClajRoom room) {
    Seq<ClajConnection> cons = pendingInfoRequests.remove(room.id);
    if (cons == null) return false;
    Log.debug("Sending state of room @ to @ pending request" + (cons.size > 1 ? "s..." : "..."), room.sid, cons.size);
    cons.each(room::sendRoomState);
    return true;
  }

  /**
   * Requests a room list (if not already) and adds the connection to the pending requests cache.
   * @return the state value. (0: refreshing, 1: up to date, 2: updating, 3: type not found)
   */
  public int requestRoomList(ClajConnection con, ClajType type) {
    LongMap<ClajRoom> rooms = types.getNull(type);
    if (rooms == null) {
      con.send(emptyList);
      return 3;
    }

    CachedRoomList cache = getListCache(type, rooms);
    if (cache.updating()) {
      cache.pending.add(con);
      return 2;
    } else if (!cache.isOutdated()) {
      con.sendStream(cache.packet);
      return 1;
    } else {
      cache.pending.add(con);
      cache.refresh(rooms);
      return 0;
    }
  }

  public boolean sendRoomList(ClajType type) { return sendRoomList(type, false); }
  public boolean sendRoomList(ClajType type, boolean force) {
    CachedRoomList cache = listCache.getNull(type);
    if (cache == null || !force && cache.updating()) return false;
    cache.send();
    return true;
  }

  public boolean refreshRoomList(ClajType type) { return refreshRoomList(type, false); }
  public boolean refreshRoomList(ClajType type, boolean force) {
    LongMap<ClajRoom> rooms = types.get(type);
    if (rooms == null) return false;
    CachedRoomList cache = getListCache(type, rooms);
    if (!force && cache.updating()) return false;
    cache.refresh(rooms);
    return true;
  }

  public void refreshRoomLists() {
    types.each((t, r) -> getListCache(t,  r).refresh(r));
  }

  protected CachedRoomList getListCache(ClajType type, LongMap<ClajRoom> fallbackRooms) {
    return listCache.get(type, () -> new CachedRoomList(type, fallbackRooms));
  }

  ////

  public void denyAction(ClajConnection con, ClajRoom room, MessageType type) {
    room.message(type);
    Events.fire(new ActionDeniedEvent(con, room, type));
  }

  protected boolean checkRoomHost(ClajConnection con, ClajRoom room, MessageType errType, String errMsg) {
    return checkRoomHost(con, room, errType, errMsg, null);
  }
  protected boolean checkRoomHost(ClajConnection con, ClajRoom room, MessageType errType, String errMsg, Object extra) {
    if (room == null || con == null) return true;
    // Only room host can close it
    if (room.isHost(con)) return false;
    denyAction(con, room, errType);
    if (extra == null) Log.warn(errMsg, con.sid, room.sid);
    else Log.warn(errMsg, con.sid, room.sid, extra);
    return true;
  }

  /**
   * Simple packet spam protection, ignored for room hosts.
   * @return whether the packet is allowed or not. If not, the client will be kicked and the room warned.
   */
  public boolean checkRateLimit(ClajConnection con, ClajRoom room) {
    boolean isRated = ClajConfig.spamLimit > 0 && (room == null || !room.isHost(con)) &&
                      !con.packetRate.allow(3000L, ClajConfig.spamLimit);
    if (isRated) rejectRateLimitedClient(con, room);
    return !isRated;
  }

  public boolean addQueue(ClajConnection con, RawPacket packet) {
    return addQueue(con.connection, packet);
  }
  /** @return whether a slot was found or not. */
  public boolean addQueue(Connection con, RawPacket packet) {
    RawPacket[] queue = packetQueue.get(con.getID(), () -> new RawPacket[packetQueueSize]);
    for (int i=0; i<queue.length; i++) {
      if (queue[i] == null) {
        queue[i] = packet;
        return true;
      }
    }
    return false;
  }

  /** @return whether a queue was removed or not. */
  public boolean removeQueue(ClajConnection con) {
    return packetQueue.remove(con.id) != null;
  }

  /** @return whether a queue was removed or not. */
  public boolean removeQueue(Connection con) {
    return packetQueue.remove(con.getID()) != null;
  }

  /** @return whether the queue has been send to the room host, or not (because no packet was queued). */
  public boolean handleQueue(ClajConnection con, ClajRoom room) {
    RawPacket[] queue = packetQueue.remove(con.id);
    if (queue != null) {
      Log.debug("Sending queued packets of connection @ to room host.", con.sid);
      for (RawPacket element : queue) {
        if (element != null) room.received(con.connection, element);
      }
      return true;
    }
    return false;
  }

  public AddressRater getAddressRate(ClajConnection con) {
     return rates.get(con.address, () -> new AddressRater(con.address));
  }

  // end region
  // region packet sending

  /** As this is mainly called from server thread, it will be posted to the main thread. */
  public void rejectObsoleteClient(ClajConnection connection) {
    if (ClajConfig.warnDeprecated) {
      Core.app.post(() -> {
        connection.send("[scarlet][[CLaJ Server]:[] Your CLaJ version is obsolete! "
                      + "Please upgrade it by installing the 'claj' mod, in the mod browser.");
        connection.close(DcReason.error);
        Log.warn("Connection @ tried to create a room but has an incompatible version.", connection.sid);
        Events.fire(new RoomCreationRejectedEvent(connection, CloseReason.obsoleteClient));
      });
    } else connection.close(DcReason.error);
  }

  /** As this is mainly called from server thread, it will be posted to the main thread. */
  public void rejectRateLimitedClient(ClajConnection connection, ClajRoom room) {
    Core.app.post(() -> {
      if (room != null) {
        room.message(MessageType.packetSpamming);
        room.disconnected(connection, DcReason.closed);
      }

      connection.close();
      Log.warn("Connection @ (@) disconnected for packet spamming.", connection.sid, connection.address);
      Events.fire(new ClientKickedEvent(connection));
    });
  }

  public void rejectRoomCreation(ClajConnection connection, CloseReason reason) {
    RoomClosedPacket p = new RoomClosedPacket();
    p.reason = reason;
    connection.send(p);
    Events.fire(new RoomCreationRejectedEvent(connection, reason));
    connection.close();
  }

  public void rejectRoomJoin(ClajConnection connection, ClajRoom room, RejectReason reason) {
    rejectRoomJoin(connection, room, room.id, reason);
  }
  protected void rejectRoomJoin(ClajConnection connection, ClajRoom room, long roomId, RejectReason reason) {
    RoomJoinDeniedPacket p = new RoomJoinDeniedPacket();
    p.roomId = room == null ? roomId : room.id;
    p.reason = reason;
    connection.send(p);
    Events.fire(new ConnectionJoinRejectedEvent(connection, room, reason));
    connection.close();
  }

  public void acceptJoinRequest(ClajConnection connection, ClajRoom room) {
    RoomJoinAcceptedPacket p = new RoomJoinAcceptedPacket();
    p.roomId = room.id;
    connection.send(p);
    Events.fire(new ConnectionPreJoinEvent(connection, room));
  }

  public void rejectRoomInfo(ClajConnection connection) {
    connection.send(RoomInfoDeniedPacket.instance);
    //TODO: an event?
    //connection.close();
  }

  // end region
  // region getters

  public long newRoomId() {
    long id;
    /* re-roll if 0 because it's used to specify an uncreated room. */
    do { id = Mathf.rand.nextLong(); }
    while (id == 0 || rooms.containsKey(id));
    return id;
  }

  public ClajRoom newRoom(ClajConnection host, ClajType type) {
    return new ClajRoom(newRoomId(), host, type);
  }

  public ClajRoom get(long roomId) {
    return rooms.get(roomId);
  }

  /** Try to find a room using the base64 encoded id. */
  public ClajRoom get(String encodedRoomId) {
    try { return get(Strings.base64ToLong(encodedRoomId)); }
    catch (Exception ignored) { return null; }
  }

  public ClajRoom find(Connection con) {
    return conToRoom.get(con.getID());
  }

  public ClajRoom find(ClajConnection con) {
    return conToRoom.get(con.id);
  }

  public static ClajConnection toClajCon(Connection con) {
    return con != null && con.getArbitraryData() instanceof ClajConnection ccon ? ccon : null;
  }

  // end region

  protected static class CachedRoomList {
    public final ClajType type;
    public final RoomListPacket packet;
    public long lastUpdate;
    public final Seq<ClajConnection> pending = new Seq<>(false, 16);
    public final ObjectSet<Long> requesting = new ObjectSet<>();
    public Timer.Task refreshTask;

    public CachedRoomList(ClajType type, LongMap<ClajRoom> rooms) {
      this.type = type;
      packet = new RoomListPacket();
      rooms.eachValue(r -> {
        if (!r.shouldRequestState()) return;
        packet.states.put(r.id, r.rawState);
        if (r.isProtected) packet.protectedRooms.add(r.id);
      });
    }

    public void remove(long room) {
      packet.states.remove(room);
      packet.protectedRooms.remove(room);
      requesting.remove(room);
    }

    public void set(ClajRoom room, boolean stateChanged) {
      if (!room.isPublic) {
        remove(room.id);
        return;
      }
      packet.states.put(room.id, room.rawState);
      if (room.isProtected) packet.protectedRooms.add(room.id);
      else packet.protectedRooms.remove(room.id);
      if (stateChanged) requesting.remove(room.id);
    }

    public void refresh(LongMap<ClajRoom> rooms) { refresh(rooms, this::send); }
    public void refresh(LongMap<ClajRoom> rooms, Runnable done) {
      lastUpdate = Time.millis();
      rooms.eachValue(r -> {
        if (r.shouldRequestState() && r.isStateOutdated(lastUpdate) && r.requestState(lastUpdate))
          requesting.add(r.id);
      });

      if (refreshTask != null) refreshTask.cancel(); // In case of
      if (!updating()) {
        refreshTask = null;
        done.run();
        return;
      }
      refreshTask = Timer.schedule(() -> {
        refreshTask = null;
        done.run();
      }, ClajConfig.listTimeout / 1000);
    }

    public void send() {
      if (refreshTask != null) {
        refreshTask.cancel();
        refreshTask = null;
      }
      requesting.clear();

      if (pending.isEmpty()) return;
      Log.debug("Sending room list of type @ to @ pending request" + (pending.size > 1 ? "s..." : "..."),
                type, pending.size);
      PreparedStream stream = StreamSender.prepare(packet); // optimization
      pending.each(c -> c.sendStream(stream));
      pending.clear();
    }

    public boolean isOutdated() {
      return Time.timeSinceMillis(lastUpdate) >= ClajConfig.listLifetime;
    }

    public boolean updating() {
      return requesting.size > 0;
    }
  }

  //TODO: clean cache
  protected static class AddressRater {
    public final String address;
    public final Ratekeeper joinRate = new Ratekeeper();
    public final Ratekeeper infoRate = new Ratekeeper();
    public final Ratekeeper listRate = new Ratekeeper();

    public AddressRater(String address) { this.address = address; }

    public boolean allowJoin() { return ClajConfig.joinLimit > 0 && joinRate.allow(60000L, ClajConfig.joinLimit); }
    public boolean allowInfo() { return joinRate.allow(3000L, 10); } //TODO: config
    public boolean allowList() { return joinRate.allow(3000L, 10); }

  }
}