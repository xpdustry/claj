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

package com.xpdustry.claj.bench;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;


/** A virtual ArcNet client: TCP connection plus the UDP channel of the registration. Driven by its loop only. */
abstract class Peer {
  static final byte CONNECTING = 0, WAIT_TCP_REG = 1, WAIT_UDP_REG = 2, REGISTERED = 3, CLOSED = 4;
  static final long KEEPALIVE_NS = 4_000_000_000L, UDP_RETRY_NS = 300_000_000L, STEP_TIMEOUT_NS = 15_000_000_000L;
  static final int UDP_RETRIES = 20, CONNECT_RETRIES = 5;

  final Loop loop;
  final ByteBuffer in, out;
  /** Whether the UDP channel is kept after the registration. */
  final boolean keepUdp;

  SocketChannel tcp;
  DatagramChannel udp;
  SelectionKey key, ukey;
  /** Connection id given by the server, also its id in the room. */
  int conId = -1;
  byte state = CONNECTING;
  boolean dirty, writeInterest, counted;
  long stateTime, lastWrite, retryAt;
  int udpRetries, connectRetries, mark;

  Peer(Loop loop, int bufferSize, boolean keepUdp) {
    this.loop = loop;
    this.keepUdp = keepUdp;
    in = ByteBuffer.allocate(bufferSize);
    out = ByteBuffer.allocate(bufferSize);
  }

  abstract void onRegistered();
  /** A claj or payload frame, between {@code pos} and {@code pos+len}. */
  abstract void onFrame(ByteBuffer body, int pos, int len);
  abstract void onClosed(String reason);
  abstract boolean alive();

  void onDatagram(ByteBuffer body, int pos, int len) {}

  // region connection

  void connect() {
    stateTime = loop.now;
    int buf = loop.bench.sockBuf;
    try {
      tcp = SocketChannel.open();
      tcp.configureBlocking(false);
      tcp.setOption(StandardSocketOptions.TCP_NODELAY, true);
      if (buf > 0) tcp.setOption(StandardSocketOptions.SO_RCVBUF, buf).setOption(StandardSocketOptions.SO_SNDBUF, buf);
      boolean done = tcp.connect(loop.bench.address);
      key = tcp.register(loop.selector, done ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT, this);
      if (done) connected();
    } catch (IOException e) { connectFailed(e); }
  }

  void finishConnect() {
    try {
      if (!tcp.finishConnect()) return;
      key.interestOps(SelectionKey.OP_READ);
      connected();
    } catch (IOException e) { connectFailed(e); }
  }

  /** The server accept backlog can be full when connecting a lot, so retry a little later. */
  private void connectFailed(IOException e) {
    if (connectRetries++ >= CONNECT_RETRIES) {
      close("connect: " + e.getMessage());
      return;
    }
    loop.connectDone(this);
    closeChannels();
    state = CONNECTING;
    retryAt = loop.now + 250_000_000L * connectRetries;
    loop.retries.add(this);
  }

  private void connected() throws IOException {
    udp = DatagramChannel.open();
    udp.configureBlocking(false);
    if (keepUdp && loop.bench.sockBuf > 0) udp.setOption(StandardSocketOptions.SO_RCVBUF, loop.bench.sockBuf);
    udp.connect(loop.bench.address);
    ukey = udp.register(loop.selector, SelectionKey.OP_READ, this);
    setState(WAIT_TCP_REG);
  }

  void setState(byte state) {
    this.state = state;
    stateTime = loop.now;
  }

  void close(String reason) {
    if (state == CLOSED) return;
    if (state < REGISTERED) loop.connectDone(this);
    state = CLOSED;
    closeChannels();
    onClosed(reason);
  }

  private void closeChannels() {
    if (key != null) key.cancel();
    if (ukey != null) ukey.cancel();
    try { if (tcp != null) tcp.close(); }
    catch (IOException ignored) {}
    try { if (udp != null) udp.close(); }
    catch (IOException ignored) {}
    key = ukey = null;
    tcp = null;
    udp = null;
  }

  /** Timeouts, retries and keep-alive. */
  void housekeeping() {
    long now = loop.now;
    switch (state) {
      case REGISTERED -> {
        if (now - lastWrite > KEEPALIVE_NS && beginFrame(2)) {
          Proto.writeKeepAlive(out);
          endFrame();
        }
      }
      case WAIT_UDP_REG -> {
        if (now - stateTime < UDP_RETRY_NS) return;
        if (udpRetries++ >= UDP_RETRIES) close("udp registration timeout");
        else {
          sendUdpRegister();
          stateTime = now;
        }
      }
      case CONNECTING -> { // not started yet if not counted
        if (counted && now - stateTime > STEP_TIMEOUT_NS) close("connect timeout");
      }
      case WAIT_TCP_REG -> {
        if (now - stateTime > STEP_TIMEOUT_NS) close("tcp registration timeout");
      }
      default -> {}
    }
  }

  private void sendUdpRegister() {
    ByteBuffer b = loop.udpBuf;
    b.clear();
    Proto.writeRegisterUdp(b, conId);
    try { udp.write(b.flip()); }
    catch (IOException ignored) {} // retried
  }

  // end region
  // region reading

  void onReadable() {
    ByteBuffer in = this.in;
    try {
      if (tcp.read(in) < 0) {
        close("closed by server");
        return;
      }
    } catch (IOException e) {
      close("read: " + e.getMessage());
      return;
    }

    in.flip();
    int lim = in.limit();
    while (in.remaining() >= 2 && state != CLOSED) {
      int pos = in.position(), len = in.getShort(pos) & 0xffff;
      if (lim - pos - 2 < len) break;
      if (len == 0) {
        error("invalid frame length");
        return;
      }
      in.position(pos + 2 + len);
      frame(in, pos + 2, len);
    }
    if (state == CLOSED) return;
    in.limit(lim).compact();
    if (!in.hasRemaining()) error("frame larger than the read buffer");
  }

  private void error(String reason) {
    loop.stats.errors++;
    close(reason);
  }

  void onUdpReadable() {
    ByteBuffer b = loop.udpBuf;
    for (int i=0; i<64 && state != CLOSED; i++) { // bounded, to not starve the others
      int n;
      try { n = udp.read(b.clear()); }
      catch (IOException e) { return; } // ICMP unreachable and co
      if (n <= 0) return;
      if (state == REGISTERED) onDatagram(b, 0, n);
    }
  }

  private void frame(ByteBuffer body, int pos, int len) {
    if (body.get(pos) != Proto.FRAMEWORK) {
      if (state == REGISTERED) onFrame(body, pos, len);
      return;
    }
    if (len < 2) return;
    switch (body.get(pos + 1)) {
      case Proto.FW_REG_TCP -> {
        if (state != WAIT_TCP_REG || len < 6) return;
        conId = body.getInt(pos + 2);
        setState(WAIT_UDP_REG);
        sendUdpRegister();
      }
      case Proto.FW_REG_UDP -> {
        if (state != WAIT_UDP_REG) return;
        setState(REGISTERED);
        loop.connectDone(this);
        lastWrite = loop.now;
        if (!keepUdp) {
          ukey.cancel();
          try { udp.close(); }
          catch (IOException ignored) {}
          udp = null;
          ukey = null;
        }
        onRegistered();
      }
      default -> {} // keep-alive, ping
    }
  }

  // end region
  // region writing

  /** Reserves space for a frame, must be followed by {@link #endFrame()} if {@code true}. */
  boolean beginFrame(int maxBody) {
    if (out.remaining() < maxBody + 2) {
      flush();
      if (out.remaining() < maxBody + 2) return false;
    }
    mark = out.position();
    out.position(mark + 2);
    return true;
  }

  void endFrame() {
    out.putShort(mark, (short)(out.position() - mark - 2));
    lastWrite = loop.now;
    if (dirty) return;
    dirty = true;
    loop.dirty.add(this);
  }

  /** Flushes a peer queued by {@link #endFrame()}. */
  void flushQueued() {
    dirty = false;
    flush();
  }

  void flush() {
    if (state == CLOSED || out.position() == 0) return;
    try { tcp.write(out.flip()); }
    catch (IOException e) {
      out.clear();
      close("write: " + e.getMessage());
      return;
    }
    out.compact();

    boolean pending = out.position() > 0;
    if (pending == writeInterest) return;
    writeInterest = pending;
    key.interestOps(pending ? SelectionKey.OP_READ | SelectionKey.OP_WRITE : SelectionKey.OP_READ);
  }

  // end region
}
