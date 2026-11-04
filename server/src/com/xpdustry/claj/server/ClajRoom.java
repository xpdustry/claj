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

import java.nio.ByteBuffer;

import arc.Events;
import arc.math.Mathf;
import arc.net.*;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Ratekeeper;
import arc.util.Time;

import com.xpdustry.claj.common.ClajNet;
import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.*;
import com.xpdustry.claj.common.util.AddressUtil;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.server.ClajEvents.*;
import com.xpdustry.claj.server.util.NetworkSpeed;


public class ClajRoom implements NetListener {
  /** Id saying that no room is created. This should be handled as an invalid id. */
  public static final long UNCREATED_ROOM = 0;
  /**
   * Id meaning that that the connection is invalid, and used to broadcast packets to all clients. <br>
   * This is used for disconnect, received and idle events, but not for connected one, as it makes no sense.
   */
  public static final int CON_BROADCAST = 0;

  protected boolean created, closed;

  /** The room id. */
  public final long id;
  /**
   * The room id encoded in an url-safe base64 string.
   * @see com.xpdustry.claj.api.ClajLink
   */
  public final String sid;
  /** The host connection of this room. */
  public final ClajConnection host;
  public final IntMap<ClajConnection> clientsMap = new IntMap<>(16);
  public final Seq<ClajConnection> clients = new Seq<>(false);
  /** For debugging, to know how many packets were transferred from a client to a host, and vice versa. */
  public final NetworkSpeed transferredPackets = new NetworkSpeed();
  /** Room state rate-limit. New states will simply be discarded. */
  public final Ratekeeper stateRate = new Ratekeeper();

  /** Creation date of the room. Sets when {@link #create()} is called. */
  public long createdAt;
  /** Closing date of the room. Sets when {@link #close()} is called. */
  public long closedAt;
  /** Whether the room will be added is public list or not. */
  public boolean isPublic;
  /** Whether the room needs a password or not to join it. */
  public boolean isProtected;
  /** The room password */
  public short password;
  /** Whether the host want, or not, the server to request his state when needed. */
  public boolean canRequestState;
  /** De-serialized room state, only present if the right decoder is present. */
  public Object state;
  /** State of the room as raw data. {@code null} if no state was received. */
  public ByteBuffer rawState;
  /** Time of the last received room state. (in ns). */
  public long lastReceivedState;
  /** Time of the last requested room state. (in ns). */
  public long lastRequestedState;
  /** Whether a state has been requested to the room. */
  public boolean requestingState;
  /** Room implementation type. Can be {@code null}. */
  public final ClajType type;
  /**
   * Maximum number of CLaJ client allowed in this room. <br>
   * {@code 0} means no limit and the value must not be higher that the server limit.
   */
  public int maxClients;

  public ClajRoom(long id, ClajConnection host, ClajType type) {
    if (id == UNCREATED_ROOM) throw new IllegalArgumentException("invalid room id");
    if (host == null) throw new IllegalArgumentException("host cannot be null");
    this.id = id;
    this.sid = Strings.longToBase64(id);
    this.host = host;
    this.type = type;
  }

  protected void setRoom(ClajConnection con) {
    if (con.room != null) {
      String msg = isHost(con) ? "the host is owning another room" : "the connection is already in another room";
      throw new IllegalArgumentException(msg);
    }
    con.room = this;
  }

  protected void removeRoom(ClajConnection con) {
    if (con.room == this) con.room = null;
  }

  /** Alerts the host that a new client is coming */
  @Override
  public void connected(Connection connection) {
    ClajConnection con = ClajRelay.toClajCon(connection);
    if (con != null) connected(con);
  }

  /** Alerts the host of the client arrival. */
  public void connected(ClajConnection connection) {
    if (closed || connection == null || !connection.isConnected() ||
        containsClient(connection)) return;
    if (!host.isConnected()) {
      close();
      return;
    }

    sendConnectionJoined(host, connection);
    clientsMap.put(connection.id, connection);
    clients.add(connection);
    setRoom(connection);
    Events.fire(new ConnectionJoinedEvent(connection, this));
  }

  /** Alerts the host that a client disconnected. This doesn't close the connection. */
  @Override
  public void disconnected(Connection connection, DcReason reason) {
    ClajConnection con = ClajRelay.toClajCon(connection);
    if (con != null) disconnected(con, reason);
  }

  /** Alerts the host that a client disconnected. This doesn't close the connection. */
  public void disconnected(ClajConnection connection, DcReason reason) {
    if (closed || connection == null/* || !connection.isConnected()*/) return;

    if (isHost(connection) || !host.isConnected()) {
      Events.fire(new ConnectionLeftEvent(connection, this));
      close();
      return;
    }

    removeRoom(connection);
    clients.remove(connection);
    boolean removed = clientsMap.remove(connection.id) != null;
    sendDisconnect(host, connection.id, reason);
    // In case of the event is received twice
    if (removed) Events.fire(new ConnectionLeftEvent(connection, this));
  }

  /** Doesn't notify the room host about a disconnected client. */
  public void disconnectedQuietly(Connection connection, DcReason reason) {
    ClajConnection con = ClajRelay.toClajCon(connection);
    if (con != null) disconnectedQuietly(con, reason);
  }

  /** Doesn't notify the room host about a disconnected client, but this does close it. */
  public void disconnectedQuietly(ClajConnection connection, DcReason reason) {
    if (closed || connection == null/* || !connection.isConnected()*/) return;

    if (isHost(connection) || !host.isConnected()) {
      Events.fire(new ConnectionLeftEvent(connection, this));
      close();
    } else {
      removeRoom(connection);
      clients.remove(connection);
      // To avoid double fire if event is received twice
      if (clientsMap.remove(connection.id) != null)
        Events.fire(new ConnectionLeftEvent(connection, this));
      connection.close(reason == null ? DcReason.closed : reason);
    }
  }

  /** Close all connections of the room. */
  public void disconnectAllClients(DcReason reason, boolean notify) {
    //if (closed) return;

    clients.each(c -> {
      removeRoom(c);
      Events.fire(new ConnectionLeftEvent(c, this));
      c.close();
    });
    clients.clear();
    clientsMap.clear();

    if (!notify || !host.isConnected()) return;
    sendDisconnect(host, CON_BROADCAST, reason);
  }

  /**
   * Wraps and re-sends the packet to the host, if it come from a connection. <br>
   * Or un-wraps and re-sends the packet to the specified connection.
   * <p>
   * Only {@link ConnectionPayloadPacket} and {@link RawPacket} are allowed.
   */
  @Override
  public void received(Connection connection, Object object) {
    if (closed || connection == null) return;

    if (isHost(connection)) {
      if (object instanceof ConnectionPayloadPacket wrap)
        received(connection, wrap);

    } else if (containsClient(connection)) {
      if (object instanceof RawPacket raw)
        received(connection, raw);
    }
  }

  public void received(ClajConnection connection, Object object) {
    if (connection == null) return;
    received(connection.connection, object);
  }

  /**
   * Unwraps the packet and sends it to the corresponding connection. <br>
   * This will notify the host if the connection is not found.
   * <p>
   * Please note that this method is mainly called from network thread.
   */
  public void received(Connection connection, ConnectionPayloadPacket wrap) {
    if (closed || !isHost(connection)) return;

    // Broadcast send
    if (wrap.conID == CON_BROADCAST) {
      //TODO: send broadcast close error, when no clients are in this room, for next major version
      // Crappy but will avoid an NPE when a client is disconnecting
      Object[] cons = clients.items;
      for (int i=0, n=clients.size; i<n; i++) {
        if (cons[i] != null) ((ClajConnection)cons[i]).send(wrap.raw, wrap.isTCP);
      }
      transferredPackets.uploadMark();
      return;
    }

    ClajConnection con = clientsMap.get(wrap.conID);

    if (con != null && con.isConnected()) {
      con.send(wrap.raw, wrap.isTCP);
      transferredPackets.uploadMark();

    // Notify that this connection doesn't exist, this case normally never happen
    } else if (host.isConnected()) {
      sendDisconnect(host, wrap.conID, DcReason.error);
    }

  }

  public void received(ClajConnection connection, ConnectionPayloadPacket wrap) {
    if (connection != null) received(connection.connection, wrap);
  }

  /**
   * We never send claj packets to anyone other than the room host,
   * framework packets are ignored and mindustry packets are saved as raw buffer.
   * <p>
   * Please note that this method is mainly called from network thread.
   */
  public void received(Connection connection, RawPacket raw) {
    if (closed || connection == null || !host.isConnected() || isHost(connection) ||
        !containsClient(connection)) return;

    sendPayload(host, connection.getID(), raw, true);
    transferredPackets.downloadMark();
  }

  public void received(ClajConnection connection, RawPacket raw) {
    if (connection != null) received(connection.connection, raw);
  }

  /** Notifies the host of an idle connection. */
  @Override
  public void idle(Connection connection) {
    if (closed || connection == null || !host.isConnected() || isHost(connection) ||
        !connection.isConnected() || !containsClient(connection)) return;
    sendIdle(host, connection.getID());
  }

  /** Notifies the host of an idle connection. */
  public void idle(ClajConnection connection) {
    if (connection == null) return;
    idle(connection.connection);
  }

  /** @return {@code true} if {@link #type} is {@code null}, the provided one is the same or {@code null},
   *          if {@link ClajConfig#acceptNoType} is {@code true}.
   */
  public boolean allowsType(ClajType type) {
    return this.type == null || this.type.equals(type) || type == null && ClajConfig.acceptNoType.get();
  }

  /** @return whether this room have clients or not. */
  public boolean isEmpty() {
    return clientsMap.isEmpty();
  }

  /** @return whether the room reached the server limit or its own. */
  public boolean isFull() {
    int cap = ClajConfig.clientLimit.get(), n = clients();
    return cap > 0 && n >= cap || maxClients > 0 && n >= maxClients;
  }

  /** @return the number of CLaJ clients in this room. */
  public int clients() {
    return clientsMap.size;
  }

  /** @return whether the room is created or not. */
  public boolean isCreataed() {
    return created;
  }

  /** @return whether the room is closed or not. */
  public boolean isClosed() {
    return closed;
  }

  /** Link this room to the host and notify it's id. Can only be called once. */
  public void create() {
    if (created || closed) return;
    created = true;
    createdAt = Time.millis();

    if (!host.isConnected()) {
      closed = created;
      closedAt = createdAt;
      return;
    }

    setRoom(host);
    sendRoomCreated(host, id);
    Events.fire(new RoomCreatedEvent(this));
  }

  public void close() {
    close(CloseReason.closed);
  }
  /**
   * Closes the room and disconnects the host and all clients. <br>
   * The room object cannot be used anymore after this.
   */
  public void close(CloseReason reason) {
    close(reason, true);
  }

  protected void close(CloseReason reason, boolean notify) {
    if (closed) return;
    closed = true; // close before kicking connections, to avoid receiving events
    closedAt = Time.millis();

    // Notify the reason to the host
    if (notify && host.isConnected()) sendRoomClosed(host, reason);
    Events.fire(new RoomClosedEvent(this));

    removeRoom(host);
    host.close();
    disconnectAllClients(DcReason.closed, false);
  }

  /** Same as {@link #close()}, but doesn't notify closure to host. */
  public void closeQuietly() {
    close(null, false);
  }

  /** Sends a message to the host and clients. */
  public void message(String text) {
    if (closed) return;
    sendMessage(host, text);
  }

  /** Sends a message the host and clients. Will be translated by the room host. */
  public void message(MessageType message) {
    if (closed) return;
    sendMessage(host, message);
  }

  /** Sends a popup to the room host. */
  public void popup(String text) {
    if (closed) return;
    sendPopup(host, text);
  }

  public void setConfiguration(boolean isPublic, boolean isProtected, short password, boolean requestState,
                               int maxClients) {
    if (closed) return;

    this.isPublic = isPublic;
    this.isProtected = isProtected;
    this.password = password;
    this.canRequestState = requestState;
    this.maxClients = maxClients;

    Events.fire(new ConfigurationChangedEvent(this));
  }

  /**
   * Only requests state if not already done.
   * @return whether the state has been requested.
   */
  public boolean requestState() {
    return requestState(Time.nanos());
  }

  /**
   * Only requests state if not already done.
   * @return whether the state has been requested.
   */
  public boolean requestState(long timeNs) {
    if (closed || !isStateRequestTimedOut(timeNs)) return false;
    lastRequestedState = timeNs;
    requestingState = true;
    sendRoomStateRequest(host);
    return true;
  }


  public void setState(ByteBuffer rawState) {
    if (closed) return;
    if (rawState != null && rawState.remaining() > RoomInfoPacket.MAX_BUFF_SIZE)
      throw new IllegalArgumentException("Buffer size must be less than " + RoomInfoPacket.MAX_BUFF_SIZE);

    lastReceivedState = Time.nanos();
    this.rawState = rawState;
    state = null; //TODO: add public decoder list
    requestingState = false;

    Events.fire(new StateChangedEvent(this));
  }

  public boolean isStateRequestTimedOut() {
    if (!requestingState) return true;
    long timeout = ClajConfig.stateTimeout.get() * 1_000_000_000L;
    return timeout > 0 && Time.timeSinceNanos(lastRequestedState) >= timeout;
  }
  public boolean isStateRequestTimedOut(long timeNs) {
    if (!requestingState) return true;
    long timeout = ClajConfig.stateTimeout.get() * 1_000_000_000L;
    return timeout > 0 && timeNs - lastRequestedState >= timeout;
  }

  public boolean isStateOutdated() {
    long lifetime = ClajConfig.stateLifetime.get() * 1_000_000_000L;
    return lifetime > 0 && Time.timeSinceNanos(lastReceivedState) >= lifetime;
  }
  public boolean isStateOutdated(long timeNs) {
    long lifetime = ClajConfig.stateLifetime.get() * 1_000_000_000L;
    return lifetime > 0 && timeNs - lastReceivedState >= lifetime;
  }

  public boolean shouldRequestState() {
    return !closed && isPublic && canRequestState;
  }

  public boolean needStateRequest(long timeNs) {
    return shouldRequestState() && isStateOutdated(timeNs) && isStateRequestTimedOut(timeNs);
  }

  /** State is only send if room {@link #isPublic}. */
  public void sendRoomState(ClajConnection connection) {
    if (closed) return;
    sendRoomState(connection, this);
  }

  /** @return whether specified connection is the room host or not. */
  public boolean isHost(Connection con) {
    return con == host.connection;
  }

  /** @return whether specified connection is the room host or not. */
  public boolean isHost(ClajConnection con) {
    return con == host;
  }

  /** @return whether the connection is the room host or one of his client. */
  public boolean contains(ClajConnection con) {
    return contains(con.connection);
  }

  /** @return whether the connection is the room host or one of his client. */
  public boolean contains(Connection con) {
    return !closed && con != null && (isHost(con) || containsClient(con));
  }

  /** @return whether the connection is in this room. */
  public boolean containsClient(ClajConnection con) {
    return containsClient(con.connection);
  }

  /** @return whether the connection is in this room. */
  public boolean containsClient(Connection con) {
    return clientsMap.containsKey(con.getID());
  }


  /** Only hashes {@link #id}. */
  @Override
  public int hashCode() {
    return Long.hashCode(id);
  }

  /** Only uses {@link #id} as identity. */
  @Override
  public boolean equals(Object o) {
    return o == this || o instanceof ClajRoom room && room.id == id;
  }

  @Override
  public String toString() {
    return sid;
  }

  // Packet sending region

  /** Packet ids for optimization. */
  private static final byte
      cjp = ClajNet.getId(ConnectionJoinPacket.class),    rap = ClajNet.getId(RoomJoinAcceptedPacket.class),
      rdp = ClajNet.getId(RoomJoinDeniedPacket.class),    ccp = ClajNet.getId(ConnectionClosedPacket.class),
      cpp = ClajNet.getId(ConnectionPayloadPacket.class), cip = ClajNet.getId(ConnectionIdlingPacket.class),
      rlp = ClajNet.getId(RoomLinkPacket.class),          rcp = ClajNet.getId(RoomClosedPacket.class),
      ctp = ClajNet.getId(ClajTextMessagePacket.class),   cmp = ClajNet.getId(ClajMessagePacket.class),
      cpp2 = ClajNet.getId(ClajPopupPacket.class),        rip = ClajNet.getId(RoomInfoPacket.class);

  public static void sendConnectionJoined(ClajConnection dest, ClajConnection joined) {
    ConnectionJoinPacket p = ClajNet.newLocalPacket(cjp);
    p.conID = joined.id;
    p.addressHash = AddressUtil.hash(joined.connection);
    dest.send(p);
  }

  public static void sendConnectionAccepted(ClajConnection dest, ClajRoom room) {
    RoomJoinAcceptedPacket p = ClajNet.newLocalPacket(rap);
    p.roomId = room.id;
    dest.send(p);
  }

  public static void sendConnectionRejected(ClajConnection dest, long roomId, RejectReason reason) {
    RoomJoinDeniedPacket p = ClajNet.newLocalPacket(rdp);
    p.roomId = roomId;
    p.reason = reason;
    dest.send(p);
  }

  public static void sendDisconnect(ClajConnection dest, int conId, DcReason reason) {
    ConnectionClosedPacket p = ClajNet.newLocalPacket(ccp);
    p.conID = conId;
    p.reason = reason == null ? DcReason.closed : reason;
    dest.send(p);
  }

  /** Note that raw packet will be freed after serialization. */
  public static void sendPayload(ClajConnection dest, int conId, RawPacket raw, boolean isTcp) {
    ConnectionPayloadPacket p = ClajNet.newLocalPacket(cpp);
    p.conID = conId;
    p.raw = raw;
    p.isTCP = isTcp;
    dest.send(p);
  }

  public static void sendIdle(ClajConnection dest, int conId) {
    ConnectionIdlingPacket p = ClajNet.newLocalPacket(cip);
    p.conID = conId;
    dest.send(p);
  }

  public static void sendRoomCreated(ClajConnection dest, long roomId) {
    RoomLinkPacket p = ClajNet.newLocalPacket(rlp);
    p.roomId = roomId;
    dest.send(p);
  }

  public static void sendRoomClosed(ClajConnection dest, CloseReason reason) {
    RoomClosedPacket p = ClajNet.newLocalPacket(rcp);
    p.reason = reason;
    dest.send(p);
  }
  public static void sendMessage(ClajConnection dest, String text) {
    ClajTextMessagePacket p = ClajNet.newLocalPacket(ctp);
    p.message = text;
    dest.send(p);
  }

  public static void sendMessage(ClajConnection dest, MessageType message) {
    ClajMessagePacket p = ClajNet.newLocalPacket(cmp);
    p.message = message;
    dest.send(p);
  }

  public static void sendPopup(ClajConnection dest, String text) {
    ClajPopupPacket p = ClajNet.newLocalPacket(cpp2);
    p.message = text;
    dest.send(p);
  }

  public static void sendRoomStateRequest(ClajConnection dest) {
    dest.send(RoomStateRequestPacket.instance);
  }

  public static void sendRoomState(ClajConnection dest, ClajRoom room) {
    RoomInfoPacket p = ClajNet.newLocalPacket(rip);
    p.roomId = room.id;
    p.isProtected = room.isProtected;
    p.type = room.type;
    p.clients = room.clientsMap.size;
    p.maxClients = room.maxClients;
    p.state = room.isPublic ? room.rawState : null;
    // Do not throw an error if buffer is above limit
    dest.send(p);
  }

  public static void sendRoomInfoRejected(ClajConnection dest) {
    dest.send(RoomInfoDeniedPacket.instance);
  }
}
