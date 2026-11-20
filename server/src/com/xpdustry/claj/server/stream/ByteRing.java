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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;


/**
 * A bounded FIFO of bytes, allocated lazily and grown by doubling up to its {@link #limit}. <br>
 * Socket reads and writes go straight in and out of it, scattering or gathering when it wraps around.
 */
public class ByteRing {
  /** First allocation, enough for a control reply or a few small frames. */
  public static final int INITIAL_CAPACITY = 256;

  /** Maximum number of queued bytes. Exceeding it is a bug of the caller, not of the peer. */
  protected int limit;
  private byte[] bytes;
  private int head, size;
  /** Reused views over {@link #bytes}, for the two segments of a wrapped read or write. */
  private final ByteBuffer[] views = new ByteBuffer[2];

  ByteRing(int limit) {
    this.limit = limit;
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** @return the number of bytes that can still be queued. */
  public int free() {
    return limit - size;
  }

  /** Drops the array of an empty ring, e.g. after a large control reply was sent. */
  public void release() {
    if (size != 0) throw new IllegalStateException("ring is not empty");
    bytes = null;
    head = 0;
    views[0] = views[1] = null;
  }

  /** Forgets every queued byte and drops the array, for a closed connection. */
  public void discard() {
    size = 0;
    release();
  }

  /** Makes sure {@code count} more bytes fit in the array, growing it if needed. */
  private void reserve(int count) {
    if (count > limit - size) throw new IllegalStateException("ring overflow: " + (size + count) + " > " + limit);
    int needed = size + count;
    if (bytes != null && needed <= bytes.length) return;

    int capacity = bytes == null ? INITIAL_CAPACITY : bytes.length;
    while (capacity < needed) capacity <<= 1;
    byte[] grown = new byte[Math.max(needed, Math.min(capacity, limit))];
    if (size > 0) {
      int first = Math.min(size, bytes.length - head);
      System.arraycopy(bytes, head, grown, 0, first);
      System.arraycopy(bytes, 0, grown, first, size - first);
    }
    bytes = grown;
    head = 0;
    views[0] = ByteBuffer.wrap(grown);
    views[1] = ByteBuffer.wrap(grown);
  }

  /** @return the array index of the logical position {@code offset}, counted from the head. */
  private int index(int offset) {
    int i = head + offset;
    return i >= bytes.length ? i - bytes.length : i;
  }

  // region writing

  public void put(int value) {
    reserve(1);
    bytes[index(size++)] = (byte)value;
  }

  public void putShort(int value) {
    reserve(2);
    bytes[index(size++)] = (byte)(value >>> 8);
    bytes[index(size++)] = (byte)value;
  }

  public void putInt(int value) {
    reserve(4);
    for (int shift = 24; shift >= 0; shift -= 8) bytes[index(size++)] = (byte)(value >>> shift);
  }

  public void putLong(long value) {
    putInt((int)(value >>> 32));
    putInt((int)value);
  }

  /** Copies the next {@code count} bytes of {@code source}, advancing its position. */
  public void put(ByteBuffer source, int count) {
    reserve(count);
    int tail = index(size);
    int first = Math.min(count, bytes.length - tail);
    source.get(bytes, tail, first);
    source.get(bytes, 0, count - first);
    size += count;
  }

  /** Overwrites two already queued bytes, e.g. the length of a frame header written before its payload. */
  public void setShort(int offset, int value) {
    if (offset < 0 || offset + 2 > size) throw new IndexOutOfBoundsException("offset: " + offset);
    bytes[index(offset)] = (byte)(value >>> 8);
    bytes[index(offset + 1)] = (byte)value;
  }

  /** Forgets the bytes queued after {@code newSize}, e.g. a frame header whose payload never came. */
  public void truncate(int newSize) {
    if (newSize < 0 || newSize > size) throw new IndexOutOfBoundsException("size: " + newSize);
    size = newSize;
  }

  // end region
  // region socket I/O

  /**
   * Reads at most {@code max} bytes from the socket, straight into the free space of the ring.
   * @return the number of bytes read, or {@code -1} at end of stream.
   */
  public int read(SocketChannel socket, int max) throws IOException {
    reserve(max);
    int tail = index(size);
    int first = Math.min(max, bytes.length - tail);
    views[0].limit(tail + first).position(tail);
    views[1].limit(max - first).position(0);
    long read = socket.read(views, 0, first < max ? 2 : 1);
    if (read > 0) size += (int)read;
    return (int)read;
  }

  /**
   * Writes as many queued bytes as the socket accepts.
   * @return the number of bytes written.
   */
  public int write(SocketChannel socket) throws IOException {
    if (size == 0) return 0;
    int first = Math.min(size, bytes.length - head);
    views[0].limit(head + first).position(head);
    views[1].limit(size - first).position(0);
    int written = (int)socket.write(views, 0, first < size ? 2 : 1);
    head = index(written);
    size -= written;
    if (size == 0) head = 0;
    return written;
  }

  // end region
}
