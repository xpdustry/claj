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

import arc.Core;
import arc.net.Connection;
import arc.net.DcReason;
import arc.util.Log;
import arc.util.Ratekeeper;

import com.xpdustry.claj.common.net.stream.PreparedStream;
import com.xpdustry.claj.common.net.stream.StreamSender;
import com.xpdustry.claj.common.packets.Packet;
import com.xpdustry.claj.common.util.AddressUtil;


public class ClajConnection {
  public final Connection connection;
  public final String address;
  public final int id;
  /** hex version of {@link #id}. */
  public final String sid;
  public final Ratekeeper packetRate;

  public ClajConnection(Connection connection) {
    this(connection, AddressUtil.get(connection), AddressUtil.encodeId(connection));
  }

  /** Internal. */
  public ClajConnection(Connection connection, String address, String encodedId) {
    this.connection = connection;
    this.address = address;
    id = connection.getID();
    sid = encodedId;
    packetRate = new Ratekeeper();
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
    } catch (Exception e) { // Should not happen
      Log.err(e);
      Log.info("Error sending packet to connection @. Disconnecting invalid client!", sid);
      connection.close(DcReason.error);
    }
  }

  public void sendStream(Packet packet) {
    StreamSender.send(connection, packet);
  }

  public void sendStream(PreparedStream stream) {
    stream.send(connection);
  }

  public void close() { close(DcReason.closed); }
  /** Delay closing to let remaining packets to be sent. */
  public void close(DcReason reason) {
    Core.app.post(() -> closeNow(reason));
  }

  public void closeNow() { closeNow(DcReason.closed); }
  public void closeNow(DcReason reason) {
    if (isConnected()) connection.close(reason);
  }
}
