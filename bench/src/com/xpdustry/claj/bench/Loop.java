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
import java.nio.ByteBuffer;
import java.nio.channels.*;

import arc.func.Cons;
import arc.math.Rand;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.TaskQueue;
import arc.util.Time;


/** A network thread, owning rooms and their flows. Nothing it owns is touched by other threads. */
final class Loop extends Thread {
  /** Payload: {@code [marker|kind][int flow][long sendNanos][padding]}. */
  static final int PAYLOAD_HEADER = 13;
  /** Must not collide with the ArcNet (-2) and CLaJ (-3, -4) ids. */
  static final int MARKER = 0x50;
  static final int ONE_WAY = 0, ECHO_REQUEST = 1, ECHO_REPLY = 2;
  static final long HOUSEKEEPING_NS = 100_000_000L, STALL_NS = 1_000_000_000L;

  final Bench bench;
  final Selector selector;
  final Seq<Room> rooms = new Seq<>(Room.class);
  final Seq<Flow> flows = new Seq<>(Flow.class);
  final Queue<Peer> connectQueue = new Queue<>();
  final Seq<Peer> retries = new Seq<>(Peer.class), dirty = new Seq<>(Peer.class);
  final TaskQueue tasks = new TaskQueue();
  /** Shared by all datagrams, as everything is single-threaded. */
  final ByteBuffer udpBuf = ByteBuffer.allocateDirect(65536);
  final Stats stats = new Stats();
  /** UDP choice of the clients, kept apart from the sizes to stay reproducible. */
  final Rand rand, sizes;
  int pendingConnects, maxPending;
  /** Refreshed for each event. */
  long now = Time.nanos();

  volatile boolean running = true;
  /** Target sends per second, {@code 0} to pause. */
  volatile double rate;
  /** Flood mode when {@code > 0}: max deliveries in flight per flow. */
  volatile int window;
  private double tokens;
  private int rr;
  /** Kept to not allocate a lambda each loop. */
  private final Cons<Flow> flood = this::flood;

  Loop(Bench bench, int id) throws IOException {
    super("bench-loop-" + id);
    setDaemon(true);
    this.bench = bench;
    selector = Selector.open();
    rand = new Rand(bench.sc.seed() * 31 + id);
    sizes = new Rand(bench.sc.seed() * 17 + id);
  }

  // region loop

  @Override
  public void run() {
    long last = Time.nanos(), lastHk = last;
    try {
      while (running) {
        long t0 = Time.nanos();
        if (selector.select(1) > 0) {
          now = Time.nanos();
          for (SelectionKey k : selector.selectedKeys()) onKey(k);
          selector.selectedKeys().clear();
        }
        long t1 = Time.nanos();

        tasks.run();
        startConnects();

        now = Time.nanos();
        send(now - last);
        last = now;
        flushDirty();
        if (now - lastHk >= HOUSEKEEPING_NS) {
          lastHk = now;
          housekeeping();
          flushDirty();
        }
        long t2 = Time.nanos();
        stats.busyNanos += t2 - t1;
        stats.wallNanos += t2 - t0;
      }
    }
    catch (ClosedSelectorException ignored) {}
    catch (Throwable t) { bench.fatal(this, t); }
    finally { shutdown(); }
  }

  private void onKey(SelectionKey k) {
    Peer p = (Peer)k.attachment();
    now = Time.nanos();
    if (!k.isValid()) return;
    if (k == p.ukey) {
      p.onUdpReadable();
      return;
    }
    int ops = k.readyOps();
    if ((ops & SelectionKey.OP_CONNECT) != 0) p.finishConnect();
    else {
      if ((ops & SelectionKey.OP_READ) != 0) p.onReadable();
      if ((ops & SelectionKey.OP_WRITE) != 0 && k.isValid()) p.flush();
    }
  }

  private void shutdown() {
    rooms.each(r -> r.broken = true); // closing is not a disconnect
    rooms.each(r -> {
      r.host.close("shutdown");
      r.clients.each(c -> c.close("shutdown"));
    });
    try { selector.close(); }
    catch (IOException ignored) {}
  }

  void post(Runnable task) {
    tasks.post(task);
    selector.wakeup();
  }

  /** Merges and resets the stats, from the loop thread. */
  void collect(Bench.Interval into, Runnable done) {
    post(() -> {
      into.merge(stats);
      stats.reset();
      done.run();
    });
  }

  // end region
  // region rooms

  void addRoom(Room room, int clients) {
    rooms.add(room);
    for (byte kind : bench.sc.pattern()) {
      if (kind == Flow.BROADCAST) addFlow(kind, room, null, room.udp);
    }
    room.addClients(clients);
    connectQueue.add(room.host);
  }

  void addFlow(byte kind, Room room, Room.Client client, boolean udp) {
    flows.add(new Flow(flows.size, kind, room, client, udp));
  }

  private void startConnects() {
    while (pendingConnects < maxPending && !connectQueue.isEmpty()) {
      Peer p = connectQueue.removeFirst();
      if (p.state == Peer.CLOSED) continue;
      p.counted = true;
      pendingConnects++;
      p.connect();
    }
  }

  void connectDone(Peer p) {
    if (!p.counted) return;
    p.counted = false;
    pendingConnects--;
  }

  private void housekeeping() {
    if (retries.any()) retries.removeAll(p -> { // rare and few
      if (p.retryAt > now) return false;
      connectQueue.add(p);
      return true;
    });
    rooms.each(Room::housekeeping);
  }

  // end region
  // region sending

  private void flushDirty() {
    dirty.each(Peer::flushQueued);
    dirty.clear();
  }

  private void flood(Flow f) {
    if (!f.active()) return;
    if (f.inflight > 0 && now - f.lastProgress > STALL_NS) f.inflight = 0; // lost datagrams
    int fan = f.fanout(), max = Math.max(window, fan);
    while (f.inflight + fan <= max && sendFlow(f)) {
      if (f.inflight == fan) f.lastProgress = now;
    }
  }

  private void send(long dt) {
    int count = flows.size;
    if (count == 0) return;
    if (window > 0) {
      flows.each(flood);
      return;
    }

    double rate = this.rate;
    if (rate <= 0) {
      tokens = 0;
      return;
    }
    tokens += rate * dt * 1e-9;
    double cap = Math.max(rate * 0.05, 1); // max burst of 50ms
    if (tokens > cap) {
      stats.lagged += (long)(tokens - cap);
      tokens = cap;
    }
    int n = (int)tokens;
    tokens -= n;
    // Round-robin resumed from the last call, so not an each()
    Flow[] fs = flows.items;
    for (int guard = n + count; n > 0 && guard > 0; guard--) {
      Flow f = fs[rr];
      if (++rr == count) rr = 0;
      if (!f.active()) continue;
      sendFlow(f);
      n--;
    }
  }

  private static void writePayload(ByteBuffer o, int kind, int flow, long time, int size) {
    o.put((byte)(MARKER | kind)).putInt(flow).putLong(time);
    o.position(o.position() + size - PAYLOAD_HEADER); // padding left as is
  }

  private boolean sendFlow(Flow f) {
    int size = sizes.random(bench.minSize, bench.maxSize);
    Room.Client c = f.client;

    if (f.kind == Flow.UP || f.kind == Flow.ECHO) {
      int kind = f.kind == Flow.ECHO ? ECHO_REQUEST : ONE_WAY;
      if (f.udp) {
        ByteBuffer b = udpBuf;
        b.clear();
        writePayload(b, kind, f.id, now, size);
        b.flip();
        try {
          if (c.udp.write(b) == 0) return blocked();
        } catch (IOException e) { return blocked(); }
      } else {
        if (!c.beginFrame(size)) return blocked();
        writePayload(c.out, kind, f.id, now, size);
        c.endFrame();
      }
    } else {
      Room.Host h = f.room.host;
      if (!h.beginFrame(Proto.WRAP_HEADER + size)) return blocked();
      bench.proto.writeWrap(h.out, c == null ? Proto.CON_BROADCAST : c.conId, !f.udp);
      writePayload(h.out, ONE_WAY, f.id, now, size);
      h.endFrame();
    }

    int fan = f.fanout();
    f.inflight += fan;
    stats.sent++;
    stats.expected += fan;
    return true;
  }

  private boolean blocked() {
    stats.blocked++;
    return false;
  }

  // end region
  // region receiving

  /** @return the payload kind, or {@code -1} if this is not a bench payload */
  int received(ByteBuffer b, int pos, int len) {
    if (len < PAYLOAD_HEADER) return -1;
    int marker = b.get(pos), fid = b.getInt(pos + 1);
    if ((marker & 0xf0) != MARKER) return -1;

    Stats s = stats;
    s.recv++;
    s.recvBytes += len;
    if (fid >= 0 && fid < flows.size) {
      Flow f = flows.items[fid];
      if (f.inflight > 0) f.inflight--;
      f.lastProgress = now;
    } else s.errors++;

    int kind = marker & 0x0f;
    if (kind != ECHO_REQUEST) s.latency.record((now - b.getLong(pos + 5)) / 1000);
    return kind;
  }

  /** Sends an echo request back to its client, with the transport of its flow. */
  void echo(Room.Host host, int conId, ByteBuffer b, int pos, int len) {
    int fid = b.getInt(pos + 1);
    boolean udp = fid >= 0 && fid < flows.size && flows.items[fid].udp;
    if (!host.beginFrame(Proto.WRAP_HEADER + len)) {
      blocked();
      return;
    }
    ByteBuffer o = host.out;
    bench.proto.writeWrap(o, conId, !udp);
    o.put((byte)(MARKER | ECHO_REPLY)).put(b.array(), b.arrayOffset() + pos + 1, len - 1);
    host.endFrame();
    stats.sent++;
  }

  // end region

  /** A stream of payloads of a room. */
  static final class Flow {
    /** client -> host, host -> client, host -> all clients (2.4.x), client -> host -> client. */
    static final byte UP = 0, DOWN = 1, BROADCAST = 2, ECHO = 3;

    final int id;
    final byte kind;
    final Room room;
    /** Sender (UP/ECHO) or receiver (DOWN), {@code null} for BROADCAST. */
    final Room.Client client;
    final boolean udp;
    /** Deliveries not received yet, used by the flood mode. */
    int inflight;
    long lastProgress;

    Flow(int id, byte kind, Room room, Room.Client client, boolean udp) {
      this.id = id;
      this.kind = kind;
      this.room = room;
      this.client = client;
      this.udp = udp;
    }

    boolean active() {
      return room.ready && !room.broken && (client == null ? room.alive > 0 : client.alive());
    }

    /** Deliveries produced by one send. */
    int fanout() {
      return kind == BROADCAST ? room.alive : kind == ECHO ? 2 : 1;
    }

    static byte parse(String kind) {
      return switch (kind) {
        case "up" -> UP;
        case "down" -> DOWN;
        case "broadcast" -> BROADCAST;
        case "echo" -> ECHO;
        default -> throw new IllegalArgumentException("unknown flow: " + kind);
      };
    }
  }
}
