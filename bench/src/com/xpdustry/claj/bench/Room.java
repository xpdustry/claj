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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import arc.struct.IntMap;
import arc.struct.Seq;

import com.xpdustry.claj.bench.Loop.Flow;


/** A room with its host and clients, all owned by one loop. Each client ends up joined or failed, never both. */
final class Room {
  final Loop loop;
  final Host host;
  /** Whether the broadcast flow uses UDP. Its clients will use UDP too. */
  final boolean udp;
  final Seq<Client> clients = new Seq<>(Client.class);

  long id;
  boolean linked, ready, broken;
  /** Clients that joined (even if gone since), that failed to join, and that are alive. */
  int joined, failed, alive;

  Room(Loop loop, boolean udp) {
    this.loop = loop;
    this.udp = udp;
    host = new Host();
  }

  /** From the loop thread. */
  void addClients(int n) {
    if (n <= 0) return;
    Bench b = loop.bench;
    if (broken) {
      b.failedClients.addAndGet(n);
      return;
    }
    for (int i=0; i<n; i++) {
      Client c = new Client(clients.size, udp || loop.rand.chance(b.sc.udp()));
      clients.add(c);
      for (byte kind : b.sc.pattern()) {
        if (kind != Flow.BROADCAST) loop.addFlow(kind, this, c, c.keepUdp);
      }
      if (linked) loop.connectQueue.add(c);
    }
  }

  private void linked(long id) {
    this.id = id;
    linked = true;
    clients.each(loop.connectQueue::add);
    checkReady();
  }

  private void clientJoined(int conId) {
    Client c = clients.find(x -> x.conId == conId && !x.joined);
    if (c == null) {
      loop.stats.errors++;
      return;
    }
    c.joined = true;
    joined++;
    alive++;
    loop.bench.joinedClients.incrementAndGet();
    checkReady();
  }

  private void clientFailed() {
    failed++;
    loop.bench.failedClients.incrementAndGet();
    checkReady();
  }

  private void checkReady() {
    if (ready || !linked || joined + failed < clients.size) return;
    if (clients.size > 0 && joined == 0) fail();
    else {
      ready = true;
      loop.bench.readyRooms.incrementAndGet();
    }
  }

  void fail() {
    if (broken) return;
    broken = true;
    int lost = clients.size - joined - failed; // not accounted yet
    if (lost > 0) {
      failed += lost;
      loop.bench.failedClients.addAndGet(lost);
    }
    if (!ready) loop.bench.failedRooms.incrementAndGet();
    host.close("room failed");
    clients.each(c -> c.close("room failed"));
  }

  /** From the loop thread, periodically. */
  void housekeeping() {
    if (broken) return;
    host.housekeeping();
    clients.each(Peer::housekeeping);
  }

  /** A setup failure, or a disconnect if the room was ready. */
  private void lost(String who, String reason, boolean disconnect) {
    if (disconnect) {
      loop.stats.disconnects++;
      loop.bench.disconnect(who + ": " + reason);
    } else loop.bench.failure(who + ": " + reason);
  }

  // region peers

  final class Host extends Peer {
    /** Stream relay: the streams by conId, {@code null} with the other protocols. */
    final IntMap<Stream> streams = loop.bench.proto.stream ? new IntMap<>() : null;
    /** Whether a stream has credit to give back, or waiting frames, that didn't fit in {@link #out}. */
    boolean owing;
    /**
     * A stream relay frame written while its stream lacks credit, and its stream; see {@link #beginPayload}.
     * Allocated at the first stall.
     */
    ByteBuffer stage;
    Stream staged;

    Host() {
      super(Room.this.loop, Room.this.loop.bench.hostBuffer, false);
    }

    @Override
    void onRegistered() {
      if (!beginFrame(64)) {
        close("unable to write room creation");
        return;
      }
      loop.bench.proto.writeCreate(out);
      endFrame();
    }

    @Override
    boolean alive() {
      return state == REGISTERED && ready && !broken;
    }

    @Override
    void housekeeping() {
      super.housekeeping();
      if (!linked && state == REGISTERED && loop.now - stateTime > STEP_TIMEOUT_NS) close("room creation timeout");
      if (owing) {
        owing = false;
        for (IntMap.Entry<Stream> e : streams) {
          window(e.key, e.value, 0);
          sendWaiting(e.value);
        }
      }
    }

    /**
     * Starts a payload frame to a client, of {@code size} bytes written next, to be ended by {@link #endPayload()}.
     * @return the buffer to write the payload in, {@code null} if it doesn't fit, now.
     */
    ByteBuffer beginPayload(int conId, int size, boolean udp) {
      if (!loop.bench.proto.stream) {
        if (!beginFrame(Proto.WRAP_HEADER + size)) return null;
        loop.bench.proto.writeWrap(out, conId, !udp);
        return out;
      }
      if (udp) {
        if (!reserve(Proto.MUX_HEADER + size)) return null;
        Proto.writeMux(out, Proto.M_DGRAM, conId, size);
        return out;
      }
      // Framed in the client's stream like the others, within the credit the relay gave.
      Stream st = streams.get(conId);
      if (st == null) return null;
      ByteBuffer target = out;
      if (st.waiting.isEmpty() && st.credit >= size + 2) {
        if (!reserve(Proto.MUX_HEADER + 2 + size)) return null;
        st.credit -= size + 2;
      } else {
        // Without credit, a host waits for it like a real one: the frame is kept, so it shows as latency.
        if (st.waitingBytes + size > Stream.MAX_WAITING) return null;
        if (stage == null) stage = ByteBuffer.allocate(Proto.MUX_HEADER + 2 + 0xffff);
        target = stage;
        stage.clear();
        staged = st;
      }
      Proto.writeMux(target, Proto.M_DATA, conId, size + 2);
      target.putShort((short)size);
      return target;
    }

    void endPayload() {
      if (!loop.bench.proto.stream) endFrame();
      else if (staged == null) queued();
      else {
        byte[] frame = new byte[stage.position()];
        stage.flip().get(frame);
        staged.waiting.add(frame);
        staged.waitingBytes += frame.length;
        staged = null;
      }
    }

    /** Sends the frames waiting for credit that the stream's credit now covers, in order. */
    private void sendWaiting(Stream st) {
      byte[] frame;
      while ((frame = st.waiting.peek()) != null && st.credit >= frame.length - Proto.MUX_HEADER) {
        if (!reserve(frame.length)) {
          owing = true;
          return;
        }
        st.waiting.poll();
        st.waitingBytes -= frame.length;
        st.credit -= frame.length - Proto.MUX_HEADER;
        out.put(frame);
        queued();
      }
    }

    /** Gives {@code credit} more bytes, plus what is owed, to a client stream. */
    private void window(int conId, Stream st, int credit) {
      st.owed += credit;
      if (st.owed == 0) return;
      if (!reserve(Proto.MUX_HEADER + 4)) {
        owing = true;
        return;
      }
      Proto.writeMux(out, Proto.M_WINDOW, conId, 4);
      out.putInt(st.owed);
      st.owed = 0;
      queued();
    }

    @Override
    void onFrame(ByteBuffer b, int pos, int len) {
      Stats s = loop.stats;
      if (loop.bench.proto.stream) {
        if (mux) muxFrame(b, pos, len);
        else switch (b.get(pos)) {
          case Proto.S_CREATED -> {
            mux = true; // the next byte is a mux frame
            linked(b.getLong(pos + 1));
          }
          case Proto.S_CLOSED -> close("room creation refused");
          default -> s.control++;
        }
        return;
      }
      if (b.get(pos) != Proto.CLAJ || len < 2) {
        s.errors++;
        return;
      }
      switch (loop.bench.proto.kindOf(b, pos)) {
        case Proto.K_PAYLOAD -> {
          if (len < Proto.WRAP_HEADER + Loop.PAYLOAD_HEADER) {
            s.errors++;
            return;
          }
          int conId = b.getInt(pos + 2), p = pos + Proto.WRAP_HEADER, plen = len - Proto.WRAP_HEADER;
          if (loop.received(b, p, plen) == Loop.ECHO_REQUEST) loop.echo(this, conId, b, p, plen);
        }
        case Proto.K_CON_JOIN ->
          clientJoined(b.getInt(pos + 2));
        case Proto.K_LINK -> {
          if (!linked) linked(b.getLong(pos + 2));
        }
        case Proto.K_IDLE -> {
          s.control++;
          s.idle++;
        }
        case Proto.K_ROOM_CLOSED ->
          close("room closed by server");
        default ->
          s.control++; // client closures are seen by the clients themselves
      }
    }

    /** A whole mux frame, header included. */
    private void muxFrame(ByteBuffer b, int pos, int len) {
      Stats s = loop.stats;
      int id = b.getInt(pos + 1), p = pos + Proto.MUX_HEADER, plen = len - Proto.MUX_HEADER;
      Stream st = streams.get(id);
      switch (b.get(pos)) {
        case Proto.M_OPEN -> {
          st = new Stream();
          streams.put(id, st);
          window(id, st, Proto.WINDOW);
        }
        case Proto.M_DATA -> {
          if (st == null) {
            s.errors++;
            return;
          }
          data(id, st, b, p, p + plen);
          if (state != CLOSED) window(id, st, plen); // consumed at once
        }
        case Proto.M_WINDOW -> {
          if (st != null && plen == 4) {
            st.credit += b.getInt(p);
            sendWaiting(st);
          }
        }
        case Proto.M_DGRAM -> {
          int kind = loop.received(b, p, plen);
          if (kind < 0) s.errors++;
          else if (kind == Loop.ECHO_REQUEST) loop.echo(this, id, b, p, plen);
        }
        case Proto.M_CONTROL -> {
          if (plen > 0 && b.get(p) == Proto.S_CLOSED) close("room closed by server");
          else s.control++;
        }
        case Proto.M_CLOSE -> {
          streams.remove(id);
          s.control++; // client closures are seen by the clients themselves
        }
        default -> s.control++; // FIN
      }
    }

    /** Stream bytes, cut anywhere: the {@code u16 length + payload} frames of the client, maybe partial. */
    private void data(int id, Stream st, ByteBuffer b, int p, int end) {
      byte[] a = b.array();
      ByteBuffer part = st.partial;
      // First the end of the frame cut by the previous DATA
      while (part != null && part.position() > 0 && p < end) {
        if (part.position() < 2) {
          part.put(a, p++, 1);
          continue;
        }
        int size = 2 + (part.getShort(0) & 0xffff);
        if (size > part.capacity()) {
          close("stream frame larger than the bench sizes");
          return;
        }
        int n = Math.min(size - part.position(), end - p);
        part.put(a, p, n);
        p += n;
        if (part.position() < size) return;
        part.clear();
        payload(id, st, part, 2, size - 2);
      }
      // Then the whole frames, in place
      while (end - p >= 2 && state != CLOSED) {
        int size = 2 + (b.getShort(p) & 0xffff);
        if (end - p < size) break;
        payload(id, st, b, p + 2, size - 2);
        p += size;
      }
      if (p == end || state == CLOSED) return;
      if (part == null) part = st.partial = ByteBuffer.allocate(Math.max(loop.bench.maxSize, 4) + 2);
      part.put(a, p, end - p);
    }

    /** The client's first frame is its index in the room, so the host knows who has this conId. */
    private void payload(int id, Stream st, ByteBuffer b, int pos, int len) {
      if (st.client == null) {
        int index = len == 4 ? b.getInt(pos) : -1;
        if (index < 0 || index >= clients.size || clients.get(index).conId >= 0) {
          loop.stats.errors++;
          return;
        }
        st.client = clients.get(index);
        st.client.conId = id;
        clientJoined(id);
        return;
      }
      int kind = loop.received(b, pos, len);
      if (kind < 0) loop.stats.errors++;
      else if (kind == Loop.ECHO_REQUEST) loop.echo(this, id, b, pos, len);
    }

    @Override
    void onClosed(String reason) {
      if (broken) return;
      lost("host", reason, ready);
      fail();
    }
  }

  /** A client stream, as the host of a stream relay room sees it. */
  static final class Stream {
    Client client;
    /** Bytes the host may still send, and the credit it has to give back. */
    int credit, owed;
    /** The start of a frame cut between two {@code DATA}, allocated if needed. */
    ByteBuffer partial;
    /** {@code DATA} frames waiting for credit, whole, and their bytes, at most {@link #MAX_WAITING}. */
    final ArrayDeque<byte[]> waiting = new ArrayDeque<>();
    int waitingBytes;
    static final int MAX_WAITING = 1 << 20;
  }

  final class Client extends Peer {
    final int index;
    boolean joined;
    /** Stream relay: whether the join was accepted, so the TCP is now a raw stream to the host. */
    boolean accepted;

    Client(int index, boolean udp) {
      super(Room.this.loop, Room.this.loop.bench.clientBuffer, udp);
      this.index = index;
    }

    @Override
    void onRegistered() {
      if (!beginFrame(64)) {
        close("unable to write join request");
        return;
      }
      loop.bench.proto.writeJoin(out, id);
      endFrame();
    }

    @Override
    boolean alive() {
      return joined && state == REGISTERED && !broken;
    }

    @Override
    void housekeeping() {
      super.housekeeping();
      if (!joined && state == REGISTERED && loop.now - stateTime > STEP_TIMEOUT_NS)
        close("join timeout");
    }

    @Override
    void onFrame(ByteBuffer b, int pos, int len) {
      if (loop.bench.proto.stream && !accepted) {
        switch (b.get(pos)) {
          case Proto.S_JOIN_ACCEPTED -> {
            accepted = true;
            // Introduces itself to the host, see Host#payload.
            if (!beginFrame(4)) close("unable to write the introduction");
            else {
              out.putInt(index);
              endFrame();
            }
          }
          case Proto.S_JOIN_DENIED -> close("join denied");
          default -> loop.stats.control++;
        }
        return;
      }
      if (loop.bench.proto.stream || b.get(pos) != Proto.CLAJ) {
        if (loop.received(b, pos, len) < 0) loop.stats.errors++;
      } else if (len >= 2 && loop.bench.proto.kindOf(b, pos) == Proto.K_JOIN_DENIED)
        close("join denied");
      else loop.stats.control++;
    }

    @Override
    void onDatagram(ByteBuffer b, int pos, int len) {
      if (b.get(pos) != Proto.FRAMEWORK && loop.received(b, pos, len) < 0)
        loop.stats.errors++;
    }

    @Override
    void onClosed(String reason) {
      if (broken) return; // accounted by the room
      if (!joined) {
        lost("client", reason, false);
        clientFailed();
      } else {
        alive--;
        if (ready) lost("client", reason, true);
      }
    }
  }

  // end region
}
