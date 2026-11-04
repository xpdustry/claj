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

package com.xpdustry.claj.server;

import java.net.InetAddress;

import arc.net.Connection;
import arc.net.DcReason;
import arc.util.Log;
import arc.util.Ratekeeper;

import com.xpdustry.claj.common.net.stream.PreparedStream;
import com.xpdustry.claj.common.net.stream.StreamSender;
import com.xpdustry.claj.common.packets.Packet;
import com.xpdustry.claj.common.packets.RawPacket;
import com.xpdustry.claj.common.util.AddressUtil;


public class ClajConnection {
  public final Connection connection;
  public final InetAddress address;
  public final String saddress;
  public final int id;
  /** hex version of {@link #id}. */
  public final String sid;
  public final Ratekeeper packetRate;
  /** Whether the connection has already been removed from the rate-limit. */
  protected boolean uncounted;

  protected volatile ClajRoom room;

  public ClajConnection(Connection connection) {
    this(connection, AddressUtil.get(connection), AddressUtil.getString(connection),
         AddressUtil.encodeId(connection));
  }

  protected ClajConnection(Connection connection, InetAddress address, String saddress, String sid) {
    if (connection == null) throw new NullPointerException("connection is null");
    if (address == null) throw new IllegalArgumentException("no address found for this connection");
    this.connection = connection;
    this.address = address;
    this.saddress = saddress;
    this.id = connection.getID();
    this.sid = sid;
    this.packetRate = new Ratekeeper();
  }

  /** The room where the connection is right now. */
  public ClajRoom currentRoom() {
    return room;
  }

  public boolean isRoomHost() {
    return room != null && room.host == this;
  }

  public boolean isConnected() {
    return connection.isConnected();
  }

  /** Send via TCP. */
  public void send(Object object) { send(object, true); }
  public void send(Object object, boolean reliable) {
    if (!isConnected()) return;
    try {
      if(reliable) connection.sendTCP(object);
      else connection.sendUDP(object);
    } catch (Throwable e) { // Should not happen
      Log.err(e);
      Log.err("Error sending packet to connection @. Disconnecting invalid client!", sid);
      close(DcReason.error);
    }
  }

  public void sendStream(Packet packet) {
    StreamSender.send(connection, packet);
  }

  public void sendStream(PreparedStream stream) {
    stream.send(connection);
  }

  public void close() { close(DcReason.closed); }
  public void close(DcReason reason) {
    connection.close(reason);
  }


  //try to get rid of this...
  /**
   * Keeps a cache of packets received from connections that are not yet in a room. (queue of 2)<br>
   * Sometimes the join packet comes after other packets, and can lead to a client-side error/timeout.
   */
  private volatile RawPacket[] waitingQueue;
  private static final int packetQueueSize = 2, packetSizeInQueue = 1 << 13;

  /** @return whether a slot was found or not. */
  public boolean addQueue(RawPacket packet) {
    if (packet.data().remaining() >= packetSizeInQueue) {
      clearQueue();
      close(DcReason.error);
      Log.warn("Connection @ kicked for sending too big packets in the queue.", sid);
      return false;
    }

    RawPacket[] queue = waitingQueue;
    if (queue == null) queue = new RawPacket[packetQueueSize];
    for (int i=0; i<queue.length; i++) {
      if (queue[i] != null) continue;
      queue[i] = packet.copy();
      waitingQueue = queue;
      return true;
    }
    waitingQueue = queue;
    return false;
  }

  public void clearQueue() {
    waitingQueue = null;
  }

  /** @return whether the queue has been sent to the room host, or not,
   *          because no packet was queued or connection is not in a room. */
  public boolean handleQueue() {
    RawPacket[] queue = waitingQueue;
    if (queue == null || room == null) return false;
    waitingQueue = null;

    Log.debug("Sending queued packets of connection @ to room host.", sid);
    for (RawPacket element : queue) {
      if (element != null) room.received(this, element);
    }
    return true;
  }

  public boolean hasQueue() {
    return waitingQueue != null;
  }
}
