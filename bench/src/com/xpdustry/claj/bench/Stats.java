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

import java.util.Arrays;


/** Counters of a loop, written by its thread only and merged on demand. */
final class Stats {
  /** Payloads sent, and deliveries they should produce. */
  long sent, expected;
  /** Payloads received, each one is a packet relayed by the server. */
  long recv, recvBytes;
  /** Sends refused by a full socket, and sends lost because the loop was late. */
  long blocked, lagged;
  /** Other claj packets received by hosts, with the idle notifications apart. */
  long control, idle;
  long disconnects, errors;
  /** Loop time outside of select, and total loop time. */
  long busyNanos, wallNanos;
  /** Delivery latency in microseconds: one-way, or round-trip for echo. */
  final Histogram latency = new Histogram();

  void merge(Stats o) {
    sent += o.sent; expected += o.expected; recv += o.recv; recvBytes += o.recvBytes;
    blocked += o.blocked; lagged += o.lagged; control += o.control; idle += o.idle;
    disconnects += o.disconnects; errors += o.errors; busyNanos += o.busyNanos; wallNanos += o.wallNanos;
    latency.merge(o.latency);
  }

  void reset() {
    sent = expected = recv = recvBytes = blocked = lagged = control = idle = disconnects = errors = 0;
    busyNanos = wallNanos = 0;
    latency.reset();
  }

  double busy() {
    return wallNanos == 0 ? 0 : (double)busyNanos / wallNanos;
  }

  /** Allocation-free log-linear histogram, 32 sub-buckets per power of two (less than 3.2% of error). */
  static final class Histogram {
    static final int SUB_BITS = 5, SUB = 1 << SUB_BITS, SIZE = (64 - SUB_BITS) * SUB;

    final long[] counts = new long[SIZE];
    long total, sum, max;

    static int index(long v) {
      if (v < SUB) return (int)Math.max(v, 0);
      int shift = 63 - Long.numberOfLeadingZeros(v) - SUB_BITS;
      return (shift + 1) * SUB + ((int)(v >>> shift) & (SUB - 1));
    }

    /** Middle of a bucket. */
    static long valueOf(int index) {
      int bucket = index >>> SUB_BITS, sub = index & (SUB - 1);
      if (bucket == 0) return sub;
      int shift = bucket - 1;
      return ((long)(SUB + sub) << shift) + ((1L << shift) >>> 1);
    }

    void record(long v) {
      counts[index(v)]++;
      total++;
      sum += v;
      if (v > max) max = v;
    }

    void merge(Histogram o) {
      if (o.total == 0) return;
      for (int i=0; i<SIZE; i++) counts[i] += o.counts[i];
      total += o.total;
      sum += o.sum;
      max = Math.max(max, o.max);
    }

    void reset() {
      if (total == 0) return;
      Arrays.fill(counts, 0);
      total = sum = max = 0;
    }

    /** @param p in [0, 1] */
    long percentile(double p) {
      if (total == 0) return 0;
      long target = Math.max(1, (long)Math.ceil(total * p)), seen = 0;
      for (int i=0; i<SIZE; i++) {
        if ((seen += counts[i]) >= target) return Math.min(valueOf(i), max);
      }
      return max;
    }

    double mean() {
      return total == 0 ? 0 : (double)sum / total;
    }
  }
}
