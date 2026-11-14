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

import java.util.Locale;

import arc.struct.LongSeq;
import arc.struct.OrderedMap;
import arc.util.OS;
import arc.util.Strings;

import com.xpdustry.claj.bench.Loop.Flow;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;


/**
 * Load parameters, shared by all the servers of a comparison.
 * @param rate total sends per second, {@code null} with {@code flowRate} or flood mode
 * @param flowRate sends per second of each flow, so the total follows the clients
 * @param window flood mode when {@code > 0}: deliveries in flight per flow
 * @param minSize payload size, picked randomly between this and {@code maxSize} for each packet
 * @param warmup unmeasured seconds before the first step, for the server JIT
 * @param settle ignored seconds at the start of each step
 */
record Scenario(
  Ramp rooms, Ramp clients, Ramp rate, Ramp flowRate, int window,
  String patternName, byte[] pattern, int minSize, int maxSize, double udp, int threads,
  double warmup, double settle, double duration, double interval, Criteria criteria,
  int connectParallel, int setupTimeout, int sockBuf, long seed
) {

  record Criteria(double maxP99, double minDelivery, double maxBlocked, double maxJoinFailures, int stopAfter) {}

  Scenario {
    require(rooms.at(0) >= 1, "--rooms must start at 1 or more");
    require(minSize >= Loop.PAYLOAD_HEADER, "--size must be at least " + Loop.PAYLOAD_HEADER);
    require(maxSize <= 16000, "--size must be at most 16000 (limit of 2.3.x servers)");
    require(minSize <= maxSize, "--size minimum is greater than its maximum");
    require(udp >= 0 && udp <= 1, "--udp must be between 0 and 1");
    require(threads >= 1, "--threads must be at least 1");
  }

  private static void require(boolean ok, String error) {
    if (!ok) throw new IllegalArgumentException(error);
  }

  int steps() {
    int n = Math.max(rooms.size(), clients.size());
    Ramp load = flowRate != null ? flowRate : rate;
    return window > 0 || load == null ? n : Math.max(n, load.size());
  }

  int roomsAt(int step) {
    return (int)rooms.at(step);
  }

  int clientsAt(int step) {
    return (int)clients.at(step);
  }

  boolean growing() {
    return !rooms.fixed() || !clients.fixed();
  }

  /** Total sends per second of a step. */
  double rateAt(int step, int flows) {
    return window > 0 ? 0 : flowRate != null ? flowRate.at(step) * flows : rate.at(step);
  }

  int flowsPerRoom() {
    int n = 0;
    for (byte k : pattern) if (k == Flow.BROADCAST) n++;
    return n;
  }

  int flowsPerClient() {
     return pattern.length - flowsPerRoom();
  }

  boolean has(byte kind) {
    for (byte k : pattern) if (k == kind) return true;
    return false;
  }

  String load() {
    return window > 0 ? "flood (window " + window + ")"
         : flowRate != null ? flowRate + "/s per flow"
         : rate + "/s";
  }

  String sizes() {
    return minSize == maxSize ? String.valueOf(minSize) : minSize + ".." + maxSize;
  }

  String describe() {
    return Strings.format(
      "rooms @ | clients/room @ | load @ | pattern @ | @ B | UDP @% | @ threads",
      rooms, clients, load(), patternName, sizes(), Math.round(udp * 100), threads
    );
  }

  /** For the result files. */
  OrderedMap<String, Object> toMap() {
    return OrderedMap.of(
      "rooms", rooms.spec, "clients", clients.spec, "load", load(), "pattern", patternName, "size", sizes(), "udp", udp,
      "threads", threads, "warmup", warmup, "settle", settle, "duration", duration, "criteria", criteria
    );
  }

  /** Parses {@code 1500}, {@code 1.5k} or {@code 2m}. */
  static double amount(String s) {
    String v = s.trim().toLowerCase(Locale.ROOT);
    double mul = v.endsWith("k") ? 1e3 : v.endsWith("m") ? 1e6 : 1;
    double n = Strings.parseDouble(mul == 1 ? v : v.substring(0, v.length() - 1), Double.NaN);
    require(!Double.isNaN(n), "invalid amount: " + s);
    return n * mul;
  }

  // region ramp

  /**
   * A value per step: fixed ({@code 100}) or {@code start:step:max}, step being {@code x1.25} or {@code +5}.
   * Values are rounded and grow by at least 1. Past its end, a ramp keeps its last value.
   */
  record Ramp(String spec, long[] values) {
    static Ramp parse(String spec) {
      String[] p = spec.trim().split(":");
      if (p.length == 1) return new Ramp(spec, new long[]{Math.round(amount(p[0]))});
      require(p.length == 3, "expected a value or 'start:step:max', got: " + spec);

      double start = amount(p[0]), max = amount(p[2]);
      boolean mul = p[1].startsWith("x");
      double step = amount(mul || p[1].startsWith("+") ? p[1].substring(1) : p[1]);
      require(mul ? step > 1 : step > 0, "invalid ramp step: " + p[1]);
      require(start <= max, "ramp start is greater than its max: " + spec);

      LongSeq values = new LongSeq();
      for (long v=Math.round(start); v <= max && values.size < 1000; v = Math.max(Math.round(mul ? v * step : v + step), v + 1))
        values.add(v);
      return new Ramp(spec, values.toArray());
    }

    long at(int step) {
      return values[Math.min(step, values.length - 1)];
    }
    int size() {

      return values.length;
    }

    boolean fixed() {
      return values.length == 1;
    }

    @Override
    public String toString() {
      return spec;
    }
  }

  // end region
  // region options

  /** Scenario command options. */
  static final class Options {
    /** Scenario defaults, that each option can override. */
    enum Profile {
      /** Fixed rooms and clients, the rate ramps up. */
      throughput("100", "4", "5k:x1.4:2m"),
      /** Rooms, clients and rate grow together. */
      growth("20:x1.25:100k", "2:x1.2:64", "4k:x1.5:100m");

      final Ramp rooms, clients, rate;

      Profile(String rooms, String clients, String rate) {
        this.rooms = Ramp.parse(rooms);
        this.clients = Ramp.parse(clients);
        this.rate = Ramp.parse(rate);
      }
    }

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Defaults of the scenario: ${COMPLETION-CANDIDATES}. (default: ${DEFAULT-VALUE})")
    Profile profile = Profile.throughput;

    @Option(names = "--rooms", paramLabel = "<n|ramp>",
            description = "Rooms. A ramp is 'start:step:max', step being 'x1.25' or '+1'. " +
                          "(throughput: 100, growth: 20:x1.25:100k)")
    Ramp rooms;

    @Option(names = "--clients", paramLabel = "<n|ramp>", description = "Clients per room. (throughput: 4, growth: 2:x1.2:64)")
    Ramp clients;

    @ArgGroup(exclusive = true, heading = "Load (one of, default from the profile):%n")
    Load load = new Load();

    static final class Load {
      @Option(names = "--rate", paramLabel = "<n|ramp>",
              description = "Total sends per second. (throughput: 5k:x1.4:2m, growth: 4k:x1.5:100m)")
      Ramp rate;

      @Option(names = "--flow-rate", paramLabel = "<n|ramp>", description = "Sends per second of each flow.")
      Ramp flowRate;

      @Option(names = "--flood", paramLabel = "<window>", description = "Closed loop, with this many packets in flight per flow.")
      int window;
    }

    @Option(names = "--pattern", paramLabel = "<flows>",
            description = {"Flows of each room, joined with '+'. (default: ${DEFAULT-VALUE})",
                           "up, down, echo (round-trip), broadcast (2.4.x only)"})
    String pattern = "up+down";

    @Option(names = "--size", paramLabel = "<bytes>",
            description = "Payload size, or a random range like 32..1024. (default: ${DEFAULT-VALUE})")
    String size = "64";

    @Option(names = "--udp", paramLabel = "<0..1>", description = "Fraction of clients using UDP. (default: ${DEFAULT-VALUE})")
    double udp;

    @Option(names = "--threads", paramLabel = "<n>", description = "Bench network threads. (default: all cores)")
    int threads = OS.cores;

    @Option(names = "--warmup", paramLabel = "<s>",
            description = "Unmeasured load first, for the server JIT. (default: ${DEFAULT-VALUE})")
    double warmup = 15;

    @Option(names = "--settle", paramLabel = "<s>",
            description = "Ignored seconds at the start of each step. (default: ${DEFAULT-VALUE})")
    double settle = 2;

    @Option(names = "--duration", paramLabel = "<s>", description = "Measured seconds of each step. (default: ${DEFAULT-VALUE})")
    double duration = 8;

    @Option(names = "--interval", paramLabel = "<s>", description = "Sampling interval. (default: ${DEFAULT-VALUE})")
    double interval = 1;

    @Option(names = "--max-p99", paramLabel = "<ms>", description = "Max p99 latency of a step. (default: ${DEFAULT-VALUE})")
    double maxP99 = 50;

    @Option(names = "--min-delivery", paramLabel = "<%>", description = "Min delivery of a step. (default: ${DEFAULT-VALUE})")
    double minDelivery = 98;

    @Option(names = "--max-blocked", paramLabel = "<%>",
            description = "Max sends blocked by full sockets. (default: ${DEFAULT-VALUE})")
    double maxBlocked = 1;

    @Option(names = "--max-join-failures", paramLabel = "<%>",
            description = "Max new rooms and clients failing to join. (default: ${DEFAULT-VALUE})")
    double maxJoinFailures = 1;

    @Option(names = "--stop-after", paramLabel = "<n>",
            description = "Failed steps in a row before stopping. (default: ${DEFAULT-VALUE})")
    int stopAfter = 2;

    @Option(names = "--connect-parallel", paramLabel = "<n>",
            description = "Connections being opened at once. (default: ${DEFAULT-VALUE})")
    int connectParallel = 32;

    @Option(names = "--setup-timeout", paramLabel = "<s>", description = "Max time to grow a step. (default: ${DEFAULT-VALUE})")
    int setupTimeout = 180;

    @Option(names = "--sockbuf", paramLabel = "<bytes>", description = "Socket buffer sizes. (default: system)")
    int sockBuf;

    @Option(names = "--seed", description = "Seed of the UDP clients selection. (default: ${DEFAULT-VALUE})")
    long seed = 42;

    Scenario toScenario() {
      boolean defaultLoad = load.rate == null && load.flowRate == null && load.window <= 0;
      int range = size.indexOf("..");
      int min = (int)amount(range < 0 ? size : size.substring(0, range));
      int max = range < 0 ? min : (int)amount(size.substring(range + 2));
      return new Scenario(
        rooms != null ? rooms : profile.rooms, clients != null ? clients : profile.clients,
        defaultLoad ? profile.rate : load.rate, load.flowRate, load.window,
        pattern, parsePattern(pattern), min, max, udp, threads,
        warmup, settle, duration, interval, new Criteria(maxP99, minDelivery, maxBlocked, maxJoinFailures, stopAfter),
        connectParallel, setupTimeout, sockBuf, seed
      );
    }

    static byte[] parsePattern(String s) {
      String[] parts = s.split("\\+");
      byte[] kinds = new byte[parts.length];
      for (int i=0; i<parts.length; i++) kinds[i] = Flow.parse(parts[i].trim());
      return kinds;
    }
  }

  // end region
}
