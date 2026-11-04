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

package com.xpdustry.claj.common.status;

import java.nio.ByteBuffer;
import java.util.Arrays;

import arc.util.Strings;
import arc.util.io.ByteBufferInput;
import arc.util.io.ByteBufferOutput;


/**
 * Must be in printable ASCII, between {@code 1} and {@code 16} <u>BYTES</u>.
 * <p>
 * Type will be truncated if greater than {@link #MAX_SIZE}, spaces will be replaced by {@code '_'},
 * and unknown characters replaced by {@code '?'}.
 */
public class ClajType {
  /** In bytes. */
  public static final int MIN_SIZE = 1, MAX_SIZE = 16;

  protected final byte[] rawType;
  protected final String type;
  protected final int hash;

  /** @see #encode(String) */
  public ClajType(String str) {
    if (str == null || str.length() < MIN_SIZE)
      throw new IllegalArgumentException("no type specified");
    rawType = encode(str);
    type = decode(rawType);
    hash = Arrays.hashCode(rawType);
  }

   /** @see #decode(byte[]) */
  public ClajType(byte[] data) {
    if (data == null || data.length < MIN_SIZE)
      throw new IllegalArgumentException("no type specified");
    type = decode(data);
    rawType = encode(type);
    hash = Arrays.hashCode(rawType);
  }

  protected ClajType(String str, byte[] data) {
    type = str;
    rawType = data;
    hash = Arrays.hashCode(rawType);
  }

  public String type() {
    return type;
  }

  public int typeSize() {
    return rawType.length;
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object object) {
    return this == object || object instanceof ClajType other && Arrays.equals(rawType, other.rawType);
  }

  @Override
  public String toString() {
    return type;
  }

  // IO utilities

  public ByteBuffer write() {
    ByteBuffer out = ByteBuffer.allocate(1 + rawType.length);
    write(out);
    return out;
  }

  public void write(ByteBuffer out) {
    out.put((byte)rawType.length);
    out.put(rawType);
  }

  public void write(ByteBufferOutput out) {
    out.write(rawType.length);
    out.write(rawType);
  }

  /** @see #decode(byte[]) */
  public static ClajType read(ByteBuffer in) {
    byte[] data = new byte[in.get()];
    in.get(data);
    return new ClajType(data);
  }

  /** @see #decode(byte[]) */
  public static ClajType read(ByteBufferInput in) {
    byte[] data = new byte[in.readByte()];
    in.readFully(data);
    return new ClajType(data);
  }

  // Encoding utilities

  /** Data will be truncated if length is greater than {@link #MAX_SIZE}. */
  public static String decode(byte[] data) {
    data = truncate(data);
    validate(data);
    return new String(data, Strings.ascii);
  }

  /** {@code str} will be truncated if length is greater than {@link #MAX_SIZE}. */
  public static byte[] encode(String str) {
    byte[] data = truncate(str.getBytes(Strings.ascii));
    validate(data);
    return data;
  }

  /** Copy data if greater than {@link #MAX_SIZE}. */
  public static byte[] truncate(byte[] data) {
    return data.length > MAX_SIZE ? Arrays.copyOf(data, MAX_SIZE) : data;
  }

  /** Replaces spaces by {@code '_'} and invalid characters by {@code '?'}, in place. */
  public static boolean validate(byte[] data) {
    boolean equals = true;
    for (int i=0; i<data.length; i++) {
      byte c = data[i];
      data[i] = (byte)(c == ' ' ? '_' : c < ' ' || c > '~' ? '?' : c);
      equals &= data[i] == c;
    }
    return equals;
  }

  // Maker

  /**
   * @return the parsed {@link ClajType}, or {@code null} if it's invalid.
   *         Else the the dedicated constructor to adapt it.
   */
  public static ClajType of(String str) {
    byte[] t = str.getBytes(Strings.ascii);
    return t.length < MIN_SIZE || t.length > MAX_SIZE || !validate(t) ? null :
           new ClajType(new String(t, Strings.ascii), t);
  }
}
