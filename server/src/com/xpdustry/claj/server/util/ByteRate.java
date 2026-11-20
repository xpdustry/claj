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

package com.xpdustry.claj.server.util;


/** A data rate with a burst, in bytes per seconds: a token bucket. (not thread-safe) */
public class ByteRate {
  /** Seconds of rate that can be sent at once. */
  public static final int BURST_SECONDS = 3;

  /** Bytes that can be taken now, negative when overdrawn. */
  public long allowance;
  protected long refilled;
  protected boolean started;

  /**
   * Refills the allowance for the time since the last call, up to {@link #BURST_SECONDS} of the rate.
   * @param nanos the time, from {@link System#nanoTime()}.
   * @param perSecond the rate, in bytes. {@code 0} or less for no limit.
   * @return the allowance, {@link Long#MAX_VALUE} without limit.
   */
  public long refill(long nanos, long perSecond) {
    if (perSecond <= 0) {
      started = false;
      return Long.MAX_VALUE;
    }
    long burst = perSecond * BURST_SECONDS;
    if (!started) {
      started = true;
      allowance = burst;
    } else if (nanos - refilled > 0) {
      double earned = (nanos - refilled) / 1e9 * perSecond; // In double: a long pause or a high rate can't overflow
      allowance = earned >= burst ? burst : Math.min(burst, allowance + (long)earned);
    }
    refilled = nanos;
    return allowance;
  }

  /** Takes the bytes. @return whether the allowance is still positive. */
  public boolean take(long bytes) {
    allowance -= bytes;
    return allowance > 0;
  }

  /** @param megabytes per second, as {@link com.xpdustry.claj.server.ClajConfig#clientRate} gives it. */
  public static long bytesPerSecond(float megabytes) {
    return (long)(megabytes * 1_000_000d);
  }
}
