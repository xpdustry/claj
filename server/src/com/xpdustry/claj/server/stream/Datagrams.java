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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

import arc.util.Log;

import static com.xpdustry.claj.server.stream.StreamProtocol.*;


/**
 * The relay's UDP socket, with Java's {@link DatagramChannel}; {@link BatchedDatagrams} batches it on Linux. <br>
 * Datagrams are never queued past the loop turn: one the socket can't take is dropped.
 */
public class Datagrams {
  /** Bounds a burst of datagrams, so that the other sockets get their turn. */
  public static final int MAX_PER_TURN = 64;

  protected final StreamRelay relay;
  protected final DatagramChannel channel;
  /** Connections by associated UDP address. */
  protected final PeerMap peers = new PeerMap();
  /** Outgoing datagram to a UDP host, with its conId prefix. */
  protected final ByteBuffer prefixed = ByteBuffer.allocate(DATAGRAM_PREFIX_SIZE + MAX_DATAGRAM_SIZE);

  /** @param batching {@link com.xpdustry.claj.server.ClajConfig#udpBatching}. */
  public static Datagrams open(StreamRelay relay, DatagramChannel channel, String batching) {
    if (batching.equals("off")) return new Datagrams(relay, channel);
    try {
      return new BatchedDatagrams(relay, channel);
    } catch (Throwable e) {
      if (batching.equals("auto")) Log.debug("UDP batching unavailable: @", e);
      else Log.warn("UDP batching unavailable, using Java's UDP: @", e);
      return new Datagrams(relay, channel);
    }
  }

  Datagrams(StreamRelay relay, DatagramChannel channel) {
    this.relay = relay;
    this.channel = channel;
  }

  /** @return {@code nio}, or {@code jni} for the batched system calls. */
  public String name() {
    return "nio";
  }

  /** Associates {@code address} with {@code con}, whose datagrams it identifies from now on. */
  public void associate(StreamConnection con, InetSocketAddress address) {
    con.udp = address;
    PeerMap.key(con, address);
    peers.put(con);
  }

  public void dissociate(StreamConnection con) {
    if (con.udp != null) peers.remove(con);
  }

  /** Drains up to {@link #MAX_PER_TURN} datagrams. */
  public void receive() {
    for (int i=0; i<MAX_PER_TURN; i++) {
      ByteBuffer in = relay.scratch;
      in.clear();
      SocketAddress from;
      try {
        from = channel.receive(in);
      } catch (IOException e) {
        Log.debug("Unable to receive a datagram: @", e);
        return;
      }
      if (from == null) return;
      in.flip();
      InetSocketAddress address = (InetSocketAddress)from;
      relay.datagram(peers.get(address), address, in);
    }
  }

  /** Sends {@code payload} to the UDP address of {@code to}, or drops it if the socket is full. */
  public void send(StreamConnection to, ByteBuffer payload) {
    try {
      channel.send(payload, to.udp);
    } catch (IOException e) {
      Log.debug("Unable to send a datagram to @: @", to.udp, e);
    }
  }

  /** Sends {@code i32 prefix + payload}, as a host datagram. */
  public void send(StreamConnection to, int prefix, ByteBuffer payload) {
    prefixed.clear();
    prefixed.putInt(prefix).put(payload).flip();
    send(to, prefixed);
  }

  /** Sends what the loop turn queued. */
  public void flush() {}
}
