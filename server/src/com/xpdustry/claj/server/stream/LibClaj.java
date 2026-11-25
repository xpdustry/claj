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

import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;


/**
 * The Linux system calls the relay needs and Java lacks, through libclaj ({@code native/claj.c}), a small JNI
 * library packed in this jar and loaded on first use. Calls return a count, a value or {@code 0}, or
 * {@code -errno}. Unavailable on other systems: the caller keeps its pure Java path.
 */
public final class LibClaj {
  /** {@code -errno} values the callers act on. */
  public static final int EAGAIN = -11, ENOSYS = -38;
  /** Upper bound of a {@code mmsghdr} vector, the kernel's {@code UIO_MAXIOV}. */
  public static final int MAX_COUNT = 1024;
  /** Option levels and names, the same on every Linux architecture packed here. */
  public static final int SOL_SOCKET = 1, IPPROTO_TCP = 6, SO_KEEPALIVE = 9, TCP_KEEPIDLE = 4, TCP_KEEPINTVL = 5,
                          TCP_KEEPCNT = 6, TCP_NOTSENT_LOWAT = 25;

  /** The build that was loaded, see {@link #target()}. */
  private static String target;
  private static final Throwable error = load();

  /** @return whether the natives are loaded. */
  public static boolean available() {
    return error == null;
  }

  /** @return why the natives aren't available, {@code null} if they are. */
  public static Throwable error() {
    return error;
  }

  /** @return {@code linux-<arch>-<gnu|musl>}, the build that was loaded, {@code null} if none. */
  public static String target() {
    return target;
  }

  /**
   * {@code recvmmsg(fd, vector, count, MSG_DONTWAIT, NULL)}.
   * @return the messages received, or {@code -errno} ({@link #EAGAIN}: nothing to receive).
   */
  public static int recvmmsg(int fd, long vector, int count) {
    checkCount(vector, count);
    return recvmmsg0(fd, vector, count);
  }

  /**
   * {@code sendmmsg(fd, vector, count, MSG_DONTWAIT)}.
   * @return how many messages from the start of the vector the socket took, or {@code -errno} for the first one
   *         ({@link #EAGAIN}: the socket buffer is full).
   */
  public static int sendmmsg(int fd, long vector, int count) {
    checkCount(vector, count);
    return sendmmsg0(fd, vector, count);
  }

  /** {@code setsockopt} of an {@code int} option. @return {@code 0} or {@code -errno}. */
  public static int setsockopt(int fd, int level, int name, int value) {
    return setsockopt0(fd, level, name, value);
  }

  /** {@code getsockopt} of an {@code int} option whose values aren't negative. @return the value or {@code -errno}. */
  public static int getsockopt(int fd, int level, int name) {
    return getsockopt0(fd, level, name);
  }

  /** @return the native address of a direct buffer's memory, its position ignored. */
  public static long address(ByteBuffer direct) {
    if (!direct.isDirect()) throw new IllegalArgumentException("not a direct buffer");
    return address0(direct);
  }

  private static void checkCount(long vector, int count) {
    if (count < 0 || count > MAX_COUNT || vector == 0 && count > 0) throw new IllegalArgumentException("bad vector");
  }

  private static native int recvmmsg0(int fd, long vector, int count);
  private static native int sendmmsg0(int fd, long vector, int count);
  private static native int setsockopt0(int fd, int level, int name, int value);
  private static native int getsockopt0(int fd, int level, int name);
  private static native long address0(Buffer direct);

  private static Throwable load() {
    try {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      if (arch.equals("amd64")) arch = "x86_64";
      if (arch.equals("arm64")) arch = "aarch64";
      if (!os.startsWith("linux")) throw new UnsupportedOperationException("only on Linux, not " + os);
      String target = "linux-" + arch + (musl() ? "-musl" : "-gnu");
      String resource = target + "/libclaj.so";
      try (InputStream in = LibClaj.class.getResourceAsStream(resource)) {
        if (in == null) throw new UnsupportedOperationException("no " + resource + " in the jar");
        Path file = Files.createTempFile("libclaj", ".so");
        try {
          Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
          System.load(file.toAbsolutePath().toString());
        } finally {
          Files.deleteIfExists(file); // Linux keeps a loaded library mapped.
        }
      }
      LibClaj.target = target;
      return null;
    } catch (Throwable e) {
      return e;
    }
  }

  /** Whether this JVM runs on musl (e.g. Alpine): its dynamic loader, {@code ld-musl-<arch>.so.1}, is mapped. */
  private static boolean musl() {
    try {
      for (String line : Files.readAllLines(Paths.get("/proc/self/maps"))) {
        if (line.contains("/ld-musl-")) return true;
      }
    } catch (Exception ignored) {}
    return false;
  }

  private LibClaj() {}
}
