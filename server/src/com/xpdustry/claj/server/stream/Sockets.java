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

import static com.xpdustry.claj.server.stream.LibClaj.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.Channel;
import java.nio.channels.SocketChannel;



/** TCP socket options beyond Java's, through libclaj on the channel's descriptor, else the runtime's, else none. */
public class Sockets {
  /** {@code jdk.net.ExtendedSocketOptions} keepalive tunables (JDK 11+, OpenJDK 8u272+), {@code null} if absent. */
  public static final SocketOption<Integer> KEEP_IDLE = extendedOption("TCP_KEEPIDLE"),
    KEEP_INTERVAL = extendedOption("TCP_KEEPINTERVAL"), KEEP_COUNT = extendedOption("TCP_KEEPCOUNT");

  /** {@code fdVal} of the JDK's socket channel class, once found. */
  protected static Field socketFd;

  @SuppressWarnings("unchecked")
  public static SocketOption<Integer> extendedOption(String name) {
    try {
      return (SocketOption<Integer>)Class.forName("jdk.net.ExtendedSocketOptions").getField(name).get(null);
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  /** The descriptor of a JDK channel: {@code --add-opens java.base/sun.nio.ch} on JDK 9+, else refused. */
  public static int descriptor(Channel channel) throws ReflectiveOperationException {
    Field field = channel.getClass().getDeclaredField("fdVal");
    field.setAccessible(true);
    return field.getInt(channel);
  }

  /** @return the descriptor of an accepted socket for libclaj, {@code -1} where it or the descriptor is unavailable. */
  public static int fd(SocketChannel socket) {
    if (!LibClaj.available()) return -1;
    try {
      if (socketFd == null) {
        Field field = socket.getClass().getDeclaredField("fdVal");
        field.setAccessible(true);
        socketFd = field;
      }
      return socketFd.getInt(socket);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  /**
   * {@code SO_KEEPALIVE}, and the delays through libclaj, else the runtime's options, else the system's delays.
   * @param fd {@link #fd(SocketChannel)}, or {@code -1} to skip libclaj.
   * @return {@code 0}, or the {@code -errno} of libclaj's refusal, after which the runtime's options were tried.
   */
  public static int keepAlive(SocketChannel socket, int fd, int idle, int interval, int count) throws IOException {
    socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
    int error = 0;
    if (fd >= 0) {
      if ((error = setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, idle)) >= 0 &&
          (error = setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, interval)) >= 0 &&
          (error = setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, count)) >= 0) return 0;
    }
    if (KEEP_IDLE != null && KEEP_INTERVAL != null && KEEP_COUNT != null) {
      try {
        socket.setOption(KEEP_IDLE, idle);
        socket.setOption(KEEP_INTERVAL, interval);
        socket.setOption(KEEP_COUNT, count);
      } catch (UnsupportedOperationException | IOException e) {
        // Declared but not supported on this system: the system's delays apply.
      }
    }
    return Math.min(error, 0);
  }

  private Sockets() {}
}
