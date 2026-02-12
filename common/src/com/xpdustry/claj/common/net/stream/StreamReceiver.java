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

package com.xpdustry.claj.common.net.stream;

import arc.net.Connection;
import arc.struct.IntMap;

import com.xpdustry.claj.common.packets.Packet;


public class StreamReceiver {
  protected static IntMap<IntMap<StreamBuilder>> sbuilders;
  protected static IntMap<StreamBuilder> cbuilders;

  /**
   * Client stream builders.
   * @return {@code null} until a stream is complete.
   * @throws RuntimeException if a chunk was received before his head.
   */
  public static Packet received(StreamPacket packet) {
    if (cbuilders == null) cbuilders = new IntMap<>(8);

    if (packet instanceof StreamHead begin)
      cbuilders.put(begin.id, new StreamBuilder(begin));

    else if (packet instanceof StreamChunk chunk) {
      StreamBuilder builder = cbuilders.get(chunk.id);
      if (builder == null)
        throw new RuntimeException("Received a StreamChunk without a StreamHead beforehand!");
      builder.add(chunk);
      if (builder.isDone()) {
        cbuilders.remove(chunk.id);
        return builder.build();
      }
    }

    return null;
  }

  /**
   * Server stream builders.
   * @return {@code null} until a stream is complete.
   * @throws RuntimeException if a chunk was received before his head.
   */
  public static Packet received(Connection connection, StreamPacket packet) {
    if (sbuilders == null) sbuilders = new IntMap<>(16);

    if (packet instanceof StreamHead begin)
      sbuilders.get(connection.getID(), () -> new IntMap<>(8))
               .put(begin.id, new StreamBuilder(begin));

    else if (packet instanceof StreamChunk chunk) {
      IntMap<StreamBuilder> builds = sbuilders.get(connection.getID());
      StreamBuilder builder = builds != null ? builds.get(chunk.id) : null;
      if (builder == null)
        throw new RuntimeException("Received a StreamChunk without a StreamHead beforehand!");
      builder.add(chunk.data);
      if (builder.isDone()) {
        builds.remove(chunk.id);
        // Must be cleared at disconnect
        //if (builds.isEmpty()) sbuilders.remove(connection.getID());
        return builder.build();
      }
    }

    return null;
  }

  /** Clears all stream builders. */
  public static void resetAll() {
    if (sbuilders != null) sbuilders.clear();
    if (cbuilders != null) cbuilders.clear();
  }

  /** Clears client stream builders. */
  public static void reset() {
    if (cbuilders != null) cbuilders.clear();
  }

  /** Removes server stream builders of a connection. Must be called at disconnect. */
  public static void reset(Connection connection) {
    if (sbuilders != null) sbuilders.remove(connection.getID());
  }
}
