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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;


/**
 * Connections by associated UDP address, without an address object per lookup: the IPv6 (or IPv4-mapped)
 * address in two longs, and the port. Open addressing with linear probing.
 */
public class PeerMap {
  /** {@code ::ffff:0:0/96}, the IPv4-mapped prefix in the low long. */
  public static final long MAPPED = 0xffff_0000_0000L;

  protected final long seed = new SecureRandom().nextLong();
  protected long[] his = new long[64], los = new long[64];
  protected int[] ports = new int[64];
  protected StreamConnection[] values = new StreamConnection[64];
  protected int size;

  /** Sets the key fields of {@code con} from its UDP address. */
  public static void key(StreamConnection con, InetSocketAddress address) {
    InetAddress ip = address.getAddress();
    byte[] bytes = ip.getAddress();
    if (ip instanceof Inet4Address) {
      con.udpHi = 0;
      con.udpLo = MAPPED | ipv4(bytes);
    } else {
      con.udpHi = bytes(bytes, 0);
      con.udpLo = bytes(bytes, 8);
      con.udpScope = ((java.net.Inet6Address)ip).getScopeId();
    }
    con.udpPort = address.getPort();
  }

  public static long ipv4(byte[] b) {
    return (b[0] & 0xffL) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | b[3] & 0xff;
  }

  public static long bytes(byte[] b, int offset) {
    long v = 0;
    for (int i=0; i<8; i++) v = v << 8 | b[offset + i] & 0xff;
    return v;
  }

  /** Keys come from the network: a full avalanche and a random seed, so sequential ports don't form runs. */
  public int slot(long hi, long lo, int port, int mask) {
    return (int)mix(mix(mix(seed ^ hi) ^ lo) ^ port) & mask;
  }

  /** The 64-bit finalizer of MurmurHash3: every input bit changes about half the output bits. */
  public static long mix(long h) {
    h = (h ^ h >>> 33) * 0xff51afd7ed558ccdL;
    h = (h ^ h >>> 33) * 0xc4ceb9fe1a85ec53L;
    return h ^ h >>> 33;
  }

  public StreamConnection get(long hi, long lo, int port) {
    int mask = values.length - 1;
    for (int i = slot(hi, lo, port, mask);; i = i + 1 & mask) {
      StreamConnection v = values[i];
      if (v == null) return null;
      if (los[i] == lo && ports[i] == port && his[i] == hi) return v;
    }
  }

  /** For the Java UDP path. */
  public StreamConnection get(InetSocketAddress address) {
    InetAddress ip = address.getAddress();
    if (ip instanceof Inet4Address) // Inet4Address.hashCode() is its address: no array copy.
      return get(0, MAPPED | ip.hashCode() & 0xffffffffL, address.getPort());
    byte[] bytes = ip.getAddress();
    return get(bytes(bytes, 0), bytes(bytes, 8), address.getPort());
  }

  /** Associates {@code con} with its key fields, replacing any connection with the same key. */
  public void put(StreamConnection con) {
    if (size * 2 >= values.length) resize(values.length * 2);
    int mask = values.length - 1;
    int i = slot(con.udpHi, con.udpLo, con.udpPort, mask);
    for (; values[i] != null; i = i + 1 & mask) {
      if (los[i] == con.udpLo && ports[i] == con.udpPort && his[i] == con.udpHi) {
        values[i] = con;
        return;
      }
    }
    his[i] = con.udpHi;
    los[i] = con.udpLo;
    ports[i] = con.udpPort;
    values[i] = con;
    size++;
  }

  /** Removes the key of {@code con}, if it maps to {@code con}. */
  public void remove(StreamConnection con) {
    int mask = values.length - 1;
    int i = slot(con.udpHi, con.udpLo, con.udpPort, mask);
    for (;; i = i + 1 & mask) {
      if (values[i] == null) return;
      if (values[i] == con) break;
    }
    values[i] = null;
    size--;
    // Backward shift: moves the following entries of the run into the hole when their home allows it.
    for (int hole = i, j = i + 1 & mask; values[j] != null; j = j + 1 & mask) {
      int home = slot(his[j], los[j], ports[j], mask);
      if ((j - home & mask) >= (j - hole & mask)) {
        his[hole] = his[j];
        los[hole] = los[j];
        ports[hole] = ports[j];
        values[hole] = values[j];
        values[j] = null;
        hole = j;
      }
    }
  }

  public void resize(int capacity) {
    StreamConnection[] v = values;
    his = new long[capacity];
    los = new long[capacity];
    ports = new int[capacity];
    values = new StreamConnection[capacity];
    size = 0;
    for (int i=0; i<v.length; i++) if (v[i] != null) put(v[i]);
    Arrays.fill(v, null);
  }
}
