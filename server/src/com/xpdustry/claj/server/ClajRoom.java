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

import java.nio.ByteBuffer;

import arc.Events;
import arc.net.*;
import arc.struct.IntMap;
import arc.util.Time;

import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.*;
import com.xpdustry.claj.common.util.AddressUtil;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.server.ClajEvents.*;
import com.xpdustry.claj.server.util.NetworkSpeed;


public class ClajRoom implements NetListener {
  protected boolean closed;

  /** The room id. */
  public final long id;
  /**
   * The room id encoded in an url-safe base64 string.
   * @see com.xpdustry.claj.api.ClajLink
   */
  public final String sid;
  /** The host connection of this room. */
  public final ClajConnection host;
  /** Using IntMap instead of Seq for faster search. */
  public final IntMap<ClajConnection> clients = new IntMap<>();
  /** For debugging, to know how many packets were transferred from a client to a host, and vice versa. */
  public final NetworkSpeed transferredPackets = new NetworkSpeed(8);

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
  /** Time of the last received room state. */
  public long lastReceivedState;
  /** Time of the last requested room state. */
  public long lastRequestedState;
  /** Whether a state has been requested to the room. */
  public boolean requestingState;
  /** Room implementation type. Can be {@code null}. */
  public final ClajType type;

  public ClajRoom(long id, ClajConnection host, ClajType type) {
    if (id == 0) throw new IllegalArgumentException("invalid room id");
    if (host == null) throw new IllegalArgumentException("host cannot be null");
    this.id = id;
    this.sid = Strings.longToBase64(id);
    this.host = host;
    this.type = type;
  }

  /** Alerts the host that a new client is coming */
  @Override
  public void connected(Connection connection) {
    ClajConnection con = ClajRelay.toClajCon(connection);
    if (con != null) connected(con);
  }

  /** Alerts the host of the client arrival. */
  public void connected(ClajConnection connection) {
    if (closed || connection == null) return;

    ConnectionJoinPacket p = new ConnectionJoinPacket();
    p.conID = connection.id;
    p.addressHash = AddressUtil.hash(connection.connection);
    host.send(p); // Assumes the host is still connected

    clients.put(connection.id, connection);
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
    if (closed || connection == null) return;

    if (isHost(connection)) {
      Events.fire(new ConnectionLeftEvent(connection, this));
      close();
      return;

    } else if (host.isConnected()) {
      ConnectionClosedPacket p = new ConnectionClosedPacket();
      p.conID = connection.id;
      p.reason = reason;
      host.send(p);
    }

    clients.remove(connection.id);
    Events.fire(new ConnectionLeftEvent(connection, this));
  }

  /** Doesn't notify the room host about a disconnected client. */
  public void disconnectedQuietly(Connection connection, DcReason reason) {
    ClajConnection con = ClajRelay.toClajCon(connection);
    if (con != null) disconnectedQuietly(con, reason);
  }

  /** Doesn't notify the room host about a disconnected client. */
  public void disconnectedQuietly(ClajConnection connection, DcReason reason) {
    if (closed || connection == null) return;

    if (isHost(connection)) {
      Events.fire(new ConnectionLeftEvent(connection, this));
      close();
    } else {
      clients.remove(connection.id);
      Events.fire(new ConnectionLeftEvent(connection, this));
    }
  }

  /**
   * Wraps and re-sends the packet to the host, if it come from a connection. <br>
   * Or un-wraps and re-sends the packet to the specified connection.
   * <p>
   * Only {@link ConnectionPacketWrapPacket} and {@link RawPacket} are allowed.
   */
  @Override
  public void received(Connection connection, Object object) {
    if (closed || connection == null) return;

    if (isHost(connection)) {
      if (object instanceof ConnectionPacketWrapPacket wrap)
        received(connection, wrap);

    } else if (clients.containsKey(connection.getID())) {
      if (object instanceof RawPacket raw)
        received(connection, raw);
    }
  }

  /**
   * Unwraps the packet and sends it to the corresponding connection. <br>
   * This will notify the host if the connection is not found.
   */
  public void received(Connection connection, ConnectionPacketWrapPacket wrap) {
    if (closed || !isHost(connection)) return;
    ClajConnection con = clients.get(wrap.conID);

    if (con != null && con.isConnected()) {
      con.send(wrap.raw, wrap.isTCP);
      transferredPackets.uploadMark();

    // Notify that this connection doesn't exist, this case normally never happen
    } else if (host.isConnected()) {
      ConnectionClosedPacket p = new ConnectionClosedPacket();
      p.conID = wrap.conID;
      p.reason = DcReason.error;
      host.send(p);
    }
  }

  /**
   * We never send claj packets to anyone other than the room host,
   * framework packets are ignored and mindustry packets are saved as raw buffer.
   */
  public void received(Connection connection, RawPacket raw) {
    if (closed || connection == null || !host.isConnected() ||
        !clients.containsKey(connection.getID())) return;

    ConnectionPacketWrapPacket p = new ConnectionPacketWrapPacket();
    p.conID = connection.getID();
    p.raw = raw.data;
    host.send(p);
    transferredPackets.downloadMark();
  }

  /** Notifies the host of an idle connection. */
  @Override
  public void idle(Connection connection) {
    ClajConnection con = ClajRelay.toClajCon(connection);
    if (con != null) idle(con);
  }

  /** Notifies the host of an idle connection. */
  public void idle(ClajConnection connection) {
    if (closed || connection == null) return;

    if (isHost(connection)) {
      // Ignore if this is the room host

    } else if (host.isConnected() && clients.containsKey(connection.id)) {
      ConnectionIdlingPacket p = new ConnectionIdlingPacket();
      p.conID = connection.id;
      host.send(p);
    }
  }

  /** Notifies the room id to the host. Must be called once. */
  public void create() {
    if (closed) return;
    createdAt = Time.millis();

    // Assume the host is still connected
    RoomLinkPacket p = new RoomLinkPacket();
    p.roomId = id;
    host.send(p);

    Events.fire(new RoomCreatedEvent(this));
  }

  /** @return whether the room is closed or not. */
  public boolean isClosed() {
    return closed;
  }

  public void close() {
    close(CloseReason.closed);
  }

  /**
   * Closes the room and disconnects the host and all clients. <br>
   * The room object cannot be used anymore after this.
   */
  public void close(CloseReason reason) {
    if (closed) return;
    closed = true; // close before kicking connections, to avoid receiving events
    closedAt = Time.millis();

    // Alert the close reason to the host
    RoomClosedPacket p = new RoomClosedPacket();
    p.reason = reason;
    host.send(p);

    host.close();
    for (ClajConnection c : clients.values()) c.close();
    clients.clear();

    Events.fire(new RoomClosedEvent(this));
  }

  /** Sends a message to the host and clients. */
  public void message(String text) {
    if (closed) return;

    // Just send to host, it will re-send it properly to all clients
    ClajTextMessagePacket p = new ClajTextMessagePacket();
    p.message = text;
    host.send(p);
  }

  /** Sends a message the host and clients. Will be translated by the room host. */
  public void message(MessageType message) {
    if (closed) return;

    ClajMessagePacket p = new ClajMessagePacket();
    p.message = message;
    host.send(p);
  }

  /** Sends a popup to the room host. */
  public void popup(String text) {
    if (closed) return;

    ClajPopupPacket p = new ClajPopupPacket();
    p.message = text;
    host.send(p);
  }

  public void setConfiguration(boolean isPublic, boolean isProtected, short password, boolean requestState) {
    if (closed) return;

    this.isPublic = isPublic;
    this.isProtected = isProtected;
    this.password = password;
    this.canRequestState = requestState;
    Events.fire(new ConfigurationChangedEvent(this));
  }

  /**
   * Only requests state if not already done.
   * @return whether the state has been requested.
   */
  public boolean requestState() {
    return requestState(Time.millis());
  }

  /**
   * Only requests state if not already done.
   * @return whether the state has been requested.
   */
  public boolean requestState(long time) {
    if (closed || !isStateRequestTimedOut(time)) return false;
    lastRequestedState = time;
    requestingState = true;
    host.send(RoomStateRequestPacket.instance);
    return true;
  }


  public void setState(ByteBuffer rawState) {
    if (closed) return;
    if (rawState != null && rawState.remaining() > RoomInfoPacket.MAX_BUFF_SIZE)
      throw new IllegalArgumentException("Buffer size must be less than " + RoomInfoPacket.MAX_BUFF_SIZE);
    lastReceivedState = Time.millis();
    this.rawState = rawState;
    state = null; //TODO: add public decoder list
    requestingState = false;
    Events.fire(new StateChangedEvent(this));
  }

  public boolean isStateRequestTimedOut() {
    return !requestingState || Time.timeSinceMillis(lastRequestedState) >= ClajConfig.stateTimeout;
  }
  public boolean isStateRequestTimedOut(long time) {
    return !requestingState || time - lastRequestedState >= ClajConfig.stateTimeout;
  }

  public boolean isStateOutdated() {
    return Time.timeSinceMillis(lastReceivedState) >= ClajConfig.stateLifetime;
  }
  public boolean isStateOutdated(long time) {
    return time - lastReceivedState >= ClajConfig.stateLifetime;
  }

  public boolean shouldRequestState() {
    return !closed && isPublic && canRequestState;
  }

  public boolean needStateRequest(long time) {
    return shouldRequestState() && isStateOutdated(time) && isStateRequestTimedOut(time);
  }

  /** State is only send if room {@link #isPublic}. */
  public void sendRoomState(ClajConnection connection) {
    if (closed) return;

    RoomInfoPacket p = new RoomInfoPacket();
    p.roomId = id;
    p.isProtected = isProtected;
    p.type = type;
    p.state = isPublic ? rawState : null;
    // Do not throw an error if buffer is above limit
    if (rawState != null && rawState.remaining() > RoomInfoPacket.SPLIT_BUFF_SIZE)
         connection.sendStream(p);
    else connection.send(p);
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
    return !closed && con != null && (isHost(con) || clients.containsKey(con.getID()));
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
}
