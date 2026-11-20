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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import arc.net.Connection;
import arc.net.DcReason;

import com.xpdustry.claj.common.packets.RoomConfigPacket;
import com.xpdustry.claj.server.ClajConnection;


/**
 * A TCP connection of the stream relay, through its three stages, wrapped by a {@link ClajConnection}. <br>
 * The Arc methods map to the stream: {@link #sendTCP(Object)} to the stage 2 replies, {@link #close(DcReason)} to
 * a graceful close. Only used by the main thread.
 */
public class StreamConnection extends Connection {
  /** Stage 1: {@code HELLO} sent, the token isn't confirmed yet. */
  public static final int STAGE_HELLO = 0;
  /** Stage 2: control requests. */
  public static final int STAGE_CONTROL = 1;
  /** Stage 3: a raw stream to the host of {@link #room}. */
  public static final int STAGE_CLIENT = 2;
  /** Stage 3: the mux of {@link #room}. */
  public static final int STAGE_HOST = 3;

  public final StreamRelay relay;
  public final SocketChannel socket;
  /** Remote address of the TCP connection, also required for the UDP association. */
  public final InetAddress address;
  public final InetSocketAddress remote;
  public final long acceptedAt;
  /** Server-wide id, one that no registered connection had when given, see {@link StreamRelay#nextId()}. */
  public final int uid;
  /** Set once admitted. */
  public ClajConnection claj;
  public int stage = STAGE_HELLO;
  /** {@code System.nanoTime()} after which the sweep closes the connection. */
  public long deadline = Long.MAX_VALUE;
  /** Stage 3: {@code System.nanoTime()} of the last bytes or datagram received. */
  public long lastReceived;
  /** Sent to the host in the {@code CLOSE} of a client closed by the relay. */
  public DcReason closeReason = DcReason.closed;
  /** Whether it only flushes {@link #out} before closing. */
  public boolean closing;
  /** Whether a closing connection flushed {@link #out}, shut its output down and discards its input until EOF. */
  public boolean draining;
  public boolean dead;
  /** Bytes reserved in the memory budget, released on close. */
  public long reserved;

  protected SelectionKey key;
  /** Interest ops last given to {@link #key}. */
  protected int ops = -1;
  /** Index in the relay's connection list. */
  protected int slot = -1;
  /** Whether it waits in the relay's list of writes at the end of the loop turn, and the next one there. */
  protected boolean dirty;
  protected StreamConnection nextDirty;
  /** Whether the socket didn't take everything: it then waits for {@code OP_WRITE}. */
  protected boolean writeBlocked;

  // region stage 1-2

  /** Single use hello token, {@code 0} once confirmed. */
  public long token;
  /** Associated UDP address, {@code null} for a TCP-only connection. */
  public InetSocketAddress udp;
  /** {@link #udp} as a {@link PeerMap} key: IPv6 or IPv4-mapped address, port, and the IPv6 scope for sends. */
  protected long udpHi, udpLo;
  protected int udpPort, udpScope;
  /** Incoming stage 1-2 messages, {@code null} once in stage 3. */
  protected ByteBuffer request;
  /** Config received before {@code ROOM_CREATE}, if any. */
  public RoomConfigPacket config;
  public int requests;
  /** Out of its data rate allowance: not read until the sweep refills it. Whether it has a rate limit. */
  public boolean throttled, limited;
  /** Part of {@link #reserved} for a reply larger than usual, released once written. */
  protected int replyReserved;

  // end region
  // region stage 3

  /** Outgoing bytes: replies, then the stream to the client or the mux frames to the host. */
  public final ByteRing out = new ByteRing(StreamProtocol.MAX_REPLY_SIZE + StreamProtocol.LENGTH_SIZE);
  public StreamRoom room;
  /** conId of a client in its room. */
  public int conId;
  /** Bytes at the head of {@link #out} that are stage 2 replies, so they don't return credit. */
  protected int controlBytes;
  /** Bytes the host allows this client to send upstream. */
  public int upCredit;
  /** Bytes the host may still send downstream. */
  public int downCredit;
  /** Downstream bytes written to the client, not yet returned to the host. */
  public int returnCredit;
  /** Whether the client ended its output, and the host got {@code FIN}. */
  public boolean clientFin;
  /** Whether the host sent {@code FIN}: the output is shut down once {@link #out} is flushed. */
  public boolean hostFin;
  public boolean outputShut;
  /** Whether the host sent a frame for this stream, which makes it a target of broadcasts. */
  public boolean acknowledged;
  /** Whether the current broadcast frame of the host is copied to this stream. */
  protected boolean broadcasting;
  /** Byte read ahead while the client had no credit, sent first on its next turn; {@code -1} if none. */
  protected int readAhead = -1;
  /** Whether the client waits in the ready queue of its room, and the next one. */
  protected boolean ready;
  protected StreamConnection nextReady;

  // end region

  public StreamConnection(StreamRelay relay, SocketChannel socket, InetSocketAddress remote, int uid, long acceptedAt) {
    this.relay = relay;
    this.socket = socket;
    this.remote = remote;
    this.address = remote.getAddress();
    this.uid = uid;
    this.acceptedAt = acceptedAt;
  }

  // region arc connection

  @Override
  public int getID() {
    return uid;
  }

  @Override
  public boolean isConnected() {
    return !dead && !closing;
  }

  /** Sends a stage 2 reply, or the {@code ROOM_CLOSED} of a host. Other objects have no stream wire. */
  @Override
  public int sendTCP(Object object) {
    return relay.send(this, object);
  }

  /** Same as {@link #sendTCP(Object)}: the relay picks the transport of the request being handled. */
  @Override
  public int sendUDP(Object object) {
    return relay.send(this, object);
  }

  @Override
  public int sendTCPBuffer(ByteBuffer buffer) {
    throw new UnsupportedOperationException("not a framed connection");
  }

  @Override
  public int sendUDPBuffer(ByteBuffer buffer) {
    throw new UnsupportedOperationException("not a framed connection");
  }

  /** Closes gracefully: the queued bytes are written first, within the linger timeout. */
  @Override
  public void close(DcReason reason) {
    if (dead || closing) return;
    if (reason != null) closeReason = reason;
    relay.linger(this);
  }

  @Override
  public InetSocketAddress getRemoteAddressTCP() {
    return remote;
  }

  @Override
  public InetSocketAddress getRemoteAddressUDP() {
    return udp;
  }

  @Override
  public int getTcpWriteBufferSize() {
    return out.size();
  }

  @Override
  public boolean isIdle() {
    return out.isEmpty();
  }

  /** The relay has its own timeouts. */
  @Override
  public void setKeepAliveTCP(int keepAliveMillis) {}
  @Override
  public void setTimeout(int timeoutMillis) {}
  @Override
  public void setIdleThreshold(float idleThreshold) {}

  // end region

  public boolean isPending() {
    return stage <= STAGE_CONTROL;
  }

  public boolean isHost() {
    return stage == STAGE_HOST;
  }

  /** @return whether the stream is over in both directions, and fully delivered. */
  public boolean isFinished() {
    return clientFin && hostFin && out.isEmpty();
  }

  @Override
  public String toString() {
    return (stage == STAGE_HOST ? "host" : stage == STAGE_CLIENT ? "client " + conId : "connection") +
           " " + address.getHostAddress();
  }
}
