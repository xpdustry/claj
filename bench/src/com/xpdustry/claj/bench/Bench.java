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
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.math.Rand;
import arc.struct.*;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Threads;
import arc.util.Time;

import com.xpdustry.claj.bench.Loop.Flow;
import com.xpdustry.claj.bench.Report.*;


/** Runs a scenario against one server. Each step grows to its size under the previous load, then measures. */
final class Bench {
  static final long SECOND = 1_000_000_000L;

  final String label;
  final InetSocketAddress address;
  final Proto proto;
  final Scenario sc;
  /** To measure the server resources, can be {@code null}. */
  final ServerProcess server;
  final int minSize, maxSize, hostBuffer, clientBuffer, sockBuf;

  final AtomicInteger readyRooms = new AtomicInteger(), failedRooms = new AtomicInteger(),
                      joinedClients = new AtomicInteger(), failedClients = new AtomicInteger();
  /** Written by the loops, each map is its own lock. */
  private final ObjectIntMap<String> failures = new ObjectIntMap<>(), disconnects = new ObjectIntMap<>();
  private volatile Throwable fatal;

  private Loop[] loops;
  // What has been given to the loops, bench thread only
  private int plannedRooms, plannedClients, clientsPerRoom, totalFlows;
  private IntSeq loopRooms, loopFlows;
  private final Rand rand;

  Bench(String label, InetSocketAddress address, Proto proto, Scenario sc, ServerProcess server) {
    this.label = label;
    this.address = address;
    this.proto = proto;
    this.sc = sc;
    this.server = server;
    minSize = sc.minSize();
    maxSize = sc.maxSize();
    sockBuf = sc.sockBuf();
    rand = new Rand(sc.seed());
    // A few frames, as thousands of peers can be created. Hosts receive from all their clients.
    int frame = Proto.WRAP_HEADER + maxSize + 2;
    hostBuffer = Math.max(32 * 1024, frame * 8);
    clientBuffer = Math.max(4 * 1024, frame * 4);
  }

  void failure(String reason) { count(failures, reason); }
  void disconnect(String reason) { count(disconnects, reason); }

  void fatal(Loop loop, Throwable t) {
    fatal = t;
    Log.err(loop.getName() + " crashed", t);
  }

  /** Groups the reasons, without the variable parts like connection ids. */
  private static void count(ObjectIntMap<String> map, String reason) {
    String key = reason.replaceAll("[0-9a-fA-F]{6,}", "#");
    synchronized (map) {
      map.increment(key);
    }
  }

  private static void clear(ObjectIntMap<String> map) {
    synchronized (map) {
      map.clear();
    }
  }

  /** Copy, most frequent first. */
  private static OrderedMap<String, Integer> snapshot(ObjectIntMap<String> map) {
    OrderedMap<String, Integer> m = new OrderedMap<>();
    synchronized (map) {
      map.keys().toSeq().sort(k -> -map.get(k)).each(k -> m.put(k, map.get(k)));
    }
    return m;
  }

  private static void logReasons(String title, ObjectIntMap<String> map) {
    OrderedMap<String, Integer> m = snapshot(map);
    Seq<String> keys = m.orderedKeys();
    for (int i=0, n=Math.min(keys.size, 8); i<n; i++)
      Log.warn("@: @x @.", title, m.get(keys.get(i)), keys.get(i));
  }

  // region run

  Run run() throws Exception {
    Run run = new Run(label, proto.toString(), sc);
    if (sc.has(Flow.BROADCAST) && proto.legacy) {
      run.error = "broadcast is not supported by 2.3.x servers";
      Log.warn("Skipped: @.", run.error);
      return run;
    }

    Log.info("Target @, protocol @.", address, proto);
    Log.info(sc.describe());
    try {
      startLoops();
      double lastRate = 0;
      for (int i=0, steps=sc.steps(), fails=0; i<steps && fatal == null; i++) {
        Grow grow = grow(sc.roomsAt(i), sc.clientsAt(i), lastRate, i == 0 ? 0 : sc.window());
        if (i == 0) {
          run.setupSeconds = grow.seconds;
          if (readyRooms.get() == 0) {
            run.error = "no room could be created " + snapshot(failures).orderedKeys();
            break;
          }
          Threads.sleep(500); // late setup packets
          collect();
          if (sc.warmup() > 0) warmup(sc.rateAt(0, totalFlows));
        }

        lastRate = sc.rateAt(i, totalFlows);
        Step step = step(i, steps, lastRate, grow);
        run.steps.add(step);
        if (step.passed()) fails = 0;
        else if (++fails >= sc.criteria().stopAfter() || step.verdict() == Verdict.BENCH_BOUND || readyRooms.get() == 0) break;
      }
    } finally {
      if (server != null) run.serverErrors = server.logErrors(); // before disconnecting everything
      stop();
    }
    run.readyRooms = readyRooms.get();
    run.failedRooms = failedRooms.get();
    run.failedClients = failedClients.get();
    if (fatal != null) run.error = "bench crashed: " + fatal;
    return run;
  }

  private void startLoops() throws IOException {
    int threads = Math.max(1, Math.min(sc.threads(), (int)sc.rooms().at(Integer.MAX_VALUE)));
    loops = new Loop[threads];
    loopRooms = new IntSeq(threads);
    loopFlows = new IntSeq(threads);
    loopRooms.setSize(threads);
    loopFlows.setSize(threads);
    for (int i=0; i<threads; i++) {
      loops[i] = new Loop(this, i);
      loops[i].maxPending = Math.max(4, sc.connectParallel() / threads);
      loops[i].start();
    }
  }

  private void stop() throws InterruptedException {
    if (loops == null) return;
    for (Loop l : loops) {
      l.running = false;
      l.selector.wakeup();
    }
    for (Loop l : loops) l.join(5000);
  }

  /** Spreads the load over the loops, by flows. */
  private void setLoad(double rate, int window) {
    for (int i=0; i<loops.length; i++) {
      loops[i].window = window;
      loops[i].rate = totalFlows == 0 ? 0 : rate * loopFlows.get(i) / totalFlows;
    }
  }

  // end region
  // region growing

  static final class Grow {
    int rooms, clients, addedRooms, addedClients, failedRooms, failedClients;
    double seconds;
    boolean timedOut;
    OrderedMap<String, Integer> reasons = new OrderedMap<>();

    boolean added() {
      return addedRooms + addedClients > 0;
    }
  }

  /** Adds rooms and clients up to the wanted size, then waits for them. */
  private Grow grow(int rooms, int clients, double rate, int window) {
    Grow g = new Grow();
    long start = Time.nanos();
    int fr = failedRooms.get(), fc = failedClients.get(), perClient = sc.flowsPerClient();

    int more = clients - clientsPerRoom;
    if (more > 0 && plannedRooms > 0) {
      for (int i=0; i<loops.length; i++) {
        Loop l = loops[i];
        l.post(() -> l.rooms.each(r -> r.addClients(more)));
        loopFlows.incr(i, loopRooms.get(i) * more * perClient);
      }
      g.addedClients = plannedRooms * more;
    }
    clientsPerRoom = Math.max(clientsPerRoom, clients);

    if (rooms > plannedRooms) {
      @SuppressWarnings("unchecked")
      Seq<Room>[] batches = new Seq[loops.length];
      for (int i=0; i<loops.length; i++) batches[i] = new Seq<>(Room.class);
      boolean bcast = sc.has(Flow.BROADCAST);
      for (int r=plannedRooms; r<rooms; r++) {
        int li = r % loops.length;
        batches[li].add(new Room(loops[li], bcast && rand.chance(sc.udp())));
        loopRooms.incr(li, 1);
        loopFlows.incr(li, sc.flowsPerRoom() + clientsPerRoom * perClient);
      }
      int n = clientsPerRoom;
      for (int i=0; i<loops.length; i++) {
        Loop l = loops[i];
        Seq<Room> batch = batches[i];
        if (batch.any()) l.post(() -> batch.each(r -> l.addRoom(r, n)));
      }
      g.addedRooms = rooms - plannedRooms;
      g.addedClients += g.addedRooms * clientsPerRoom;
      plannedRooms = rooms;
    }

    plannedClients += g.addedClients;
    g.rooms = plannedRooms;
    g.clients = clientsPerRoom;
    totalFlows = loopFlows.sum();
    setLoad(rate, window); // over the new flows too
    if (!g.added()) return g;

    Log.info(
      "Growing to @ rooms x @ clients: +@ rooms, +@ clients@.",
      plannedRooms, clientsPerRoom, g.addedRooms, g.addedClients, rate > 0 || window > 0 ? ", under load" : ""
    );
    long deadline = start + sc.setupTimeout() * SECOND, lastPrint = start;
    while (!grown() && Time.nanos() < deadline && fatal == null) {
      Threads.sleep(20);
      if (Time.timeSinceNanos(lastPrint) < SECOND) continue;
      lastPrint = Time.nanos();
      Log.debug(
        "Rooms @/@ ready (@ failed), clients @/@ joined (@ failed).",
        readyRooms.get(), plannedRooms, failedRooms.get(), joinedClients.get(), plannedClients, failedClients.get()
      );
    }
    g.timedOut = !grown();
    g.seconds = Time.timeSinceNanos(start) / 1e9;
    g.failedRooms = failedRooms.get() - fr;
    g.failedClients = failedClients.get() - fc;

    String msg = "Grown in @s: @/@ rooms ready, @/@ clients joined, @ rooms and @ clients failed@.";
    Object[] args = {
      Strings.fixed((float)g.seconds, 1), readyRooms.get(), plannedRooms, joinedClients.get(), plannedClients,
      g.failedRooms, g.failedClients, g.timedOut ? ", timed out" : ""
    };
    if (g.failedRooms + g.failedClients > 0 || g.timedOut) Log.warn(msg, args);
    else Log.info(msg, args);
    if (failures.size > 0) {
      g.reasons = snapshot(failures);
      logReasons("Join failures", failures);
      if (server == null)
        Log.warn("All bench connections come from one address, so the server limits probably apply. " +
                 "Use the settings of 'claj-bench config' on the server.");
      clear(failures);
    }
    return g;
  }

  private boolean grown() {
    return readyRooms.get() + failedRooms.get() >= plannedRooms &&
           joinedClients.get() + failedClients.get() >= plannedClients;
  }

  // end region
  // region measuring

  /** Unmeasured load, for the server JIT. */
  private void warmup(double rate) throws InterruptedException {
    Log.info(
      "Warming up for @s @, not measured.",
      (int)sc.warmup(), sc.window() > 0 ? "in flood mode" : "at " + Report.amount(rate) + "/s"
    );
    setLoad(rate, sc.window());
    long end = Time.nanos() + (long)(sc.warmup() * SECOND), last = Time.nanos();
    while (Time.nanos() < end && fatal == null) {
      Threads.sleep(Math.max(0, Time.nanosToMillis(Math.min(SECOND, end - Time.nanos()))));
      Interval iv = collect();
      long now = Time.nanos();
      logSample("warmup", iv, (now - last) / 1e9, null);
      last = now;
    }
    setLoad(0, 0);
    Threads.sleep(300);
    collect();
    clear(disconnects);
  }

  private Step step(int index, int count, double rate, Grow grow) throws Exception {
    String load = sc.window() > 0 ? "flood w" + sc.window() : Report.amount(rate) + "/s";
    String name = sc.growing() ? grow.rooms + "x" + grow.clients + " " + load : load;
    Measure m = new Measure(name, rate, sc.window(), grow);
    Log.info(
      "Step @/@: @, @ rooms x @ clients (@ connections).",
      index + 1, count, load, grow.rooms, grow.clients, grow.rooms * (grow.clients + 1)
    );
    clear(disconnects);
    setLoad(rate, sc.window());

    long interval = (long)(sc.interval() * SECOND), start = Time.nanos(), last = start;
    long settled = start + (long)(sc.settle() * SECOND), end = settled + (long)(sc.duration() * SECOND);
    long cpuLast = server == null ? -1 : server.cpuNanos();
    collect();

    for (long next = start + interval; next <= end + interval / 2 && fatal == null; next += interval) {
      Threads.sleep(Math.max(0, Time.nanosToMillis(next - Time.nanos())));
      Interval iv = collect();
      long now = Time.nanos();
      double secs = (now - last) / 1e9;
      last = now;
      Double cpu = null;
      if (server != null) {
        long c = server.cpuNanos();
        if (c >= 0 && cpuLast >= 0) cpu = (c - cpuLast) / 1e7 / secs;
        cpuLast = c;
      }
      boolean measuring = now > settled;
      if (measuring) m.add(iv, secs, cpu, server == null ? null : server.rssMb());
      logSample(measuring ? "sample" : "settle", iv, secs, cpu);
    }

    setLoad(0, 0);
    Step step = m.result(sc.criteria(), snapshot(disconnects));
    Threads.sleep(300); // in-flight packets
    collect();
    String msg = "Step @/@: @, relayed @/s, delivery @, p99 @ ms, cpu @.";
    Object[] args = {
      index + 1, count, step.verdictText(), Report.amount(step.relayedPps()), Report.pct(step.deliveryPct()),
      Report.ms(step.p99()), Report.cpu(step.serverCpu())
    };
    if (step.passed()) Log.info(msg, args);
    else Log.warn(msg, args);
    logReasons("Disconnects", disconnects);
    return step;
  }

  /** Accumulates the intervals of a step. */
  private static final class Measure {
    final String name;
    final double target;
    final int window;
    final Grow grow;
    final Stats total = new Stats();
    double seconds, maxBusy, cpuSum, cpuMax = -1, rssMax = -1;
    int cpuSamples;

    Measure(String name, double target, int window, Grow grow) {
      this.name = name;
      this.target = target;
      this.window = window;
      this.grow = grow;
    }

    void add(Interval iv, double secs, Double cpu, Double rss) {
      total.merge(iv.stats);
      seconds += secs;
      maxBusy = Math.max(maxBusy, iv.maxBusy);
      if (cpu != null) {
        cpuSum += cpu;
        cpuSamples++;
        cpuMax = Math.max(cpuMax, cpu);
      }
      if (rss != null) rssMax = Math.max(rssMax, rss);
    }

    Step result(Scenario.Criteria c, OrderedMap<String, Integer> disconnectReasons) {
      Stats s = total;
      Stats.Histogram h = s.latency;
      double t = Math.max(seconds, 1e-9), p99 = h.percentile(0.99) / 1e3;
      double delivery = s.expected == 0 ? 0 : 100.0 * s.recv / s.expected;
      double blocked = s.sent + s.blocked == 0 ? 0 : 100.0 * s.blocked / (s.sent + s.blocked);
      double lagged = target <= 0 ? 0 : 100.0 * s.lagged / (target * t);
      int added = grow.addedRooms + grow.addedClients;
      double joinFailures = added == 0 ? 0 : 100.0 * (grow.failedRooms + grow.failedClients) / added;

      Seq<String> reasons = new Seq<>(String.class);
      if (s.recv == 0) reasons.add("nothing relayed");
      if (delivery < c.minDelivery()) reasons.add("delivery " + Report.pct(delivery));
      if (p99 > c.maxP99()) reasons.add("p99 " + Report.ms(p99) + " ms");
      if (blocked > c.maxBlocked()) reasons.add("blocked " + Report.pct(blocked));
      if (s.disconnects > 0) reasons.add(s.disconnects + " disconnects");
      if (joinFailures > c.maxJoinFailures()) reasons.add("join failures " + Report.pct(joinFailures));
      if (grow.timedOut) reasons.add("grow timeout");
      boolean bound = lagged > 2 || maxBusy > 0.95;
      if (bound) reasons.add("bench busy " + Math.round(maxBusy * 100) + "%, lag " + Report.pct(lagged));

      return new Step(
        name, grow.rooms, grow.clients, grow.rooms * (grow.clients + 1), grow.added() ? grow.seconds : 0, grow.timedOut,
        joinFailures, target, window, seconds,
        s.sent / t, s.recv / t, s.recvBytes * 8 / t / 1e6, delivery,
        h.percentile(0.5) / 1e3, h.percentile(0.9) / 1e3, p99, h.percentile(0.999) / 1e3, h.max / 1e3, h.mean() / 1e3,
        blocked, lagged, s.disconnects, s.errors, s.idle / t, s.control / t,
        cpuSamples == 0 ? null : cpuSum / cpuSamples, cpuMax < 0 ? null : cpuMax, rssMax < 0 ? null : rssMax, maxBusy * 100,
        bound ? Verdict.BENCH_BOUND : reasons.isEmpty() ? Verdict.PASS : Verdict.FAIL, reasons, grow.reasons, disconnectReasons
      );
    }
  }

  // end region
  // region stats

  /** Stats of all loops for an interval, with the busiest loop. */
  static final class Interval {
    final Stats stats = new Stats();
    double maxBusy;

    synchronized void merge(Stats s) {
      stats.merge(s);
      maxBusy = Math.max(maxBusy, s.busy());
    }
  }

  private Interval collect() throws InterruptedException {
    Interval iv = new Interval();
    CountDownLatch latch = new CountDownLatch(loops.length);
    for (Loop l : loops) {
      if (l.isAlive()) l.collect(iv, latch::countDown);
      else latch.countDown();
    }
    if (!latch.await(5, TimeUnit.SECONDS)) Log.warn("A bench thread did not answer in time.");
    return iv;
  }

  private static void logSample(String kind, Interval iv, double secs, Double cpu) {
    Stats.Histogram h = iv.stats.latency;
    Log.debug(String.format(
      "%-6s  sent %8s/s  relayed %8s/s  p50 %6s  p99 %6s  max %6s ms  blocked %d  bench %3.0f%%%s",
      kind, Report.amount(iv.stats.sent / secs), Report.amount(iv.stats.recv / secs), Report.ms(h.percentile(0.5) / 1e3),
      Report.ms(h.percentile(0.99) / 1e3), Report.ms(h.max / 1e3), iv.stats.blocked, iv.maxBusy * 100,
      cpu == null ? "" : String.format("  cpu %3.0f%%", cpu)
    ));
  }


  // end region
}
