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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;

import arc.util.Log;


import static com.xpdustry.claj.server.stream.LibClaj.EAGAIN;
import static com.xpdustry.claj.server.stream.StreamProtocol.*;


/**
 * {@link Datagrams} with {@code recvmmsg} and {@code sendmmsg} through libclaj, on the channel's descriptor, in
 * direct memory laid out for 64-bit Linux. Sends are queued during the turn and sent at its end, or before the
 * receive slots they point into are reused. A full socket buffer drops the rest of the batch.
 */
public class BatchedDatagrams extends Datagrams {
  /** Datagrams per system call. */
  public static final int BATCH = 32;
  /** {@code struct mmsghdr}: a {@code struct msghdr} (56 B) then {@code msg_len}, and the offsets in it. */
  public static final int MMSGHDR = 64, NAME = 0, NAMELEN = 8, IOV = 16, IOVLEN = 24, FLAGS = 48, LENGTH = 56;
  /** {@code struct iovec}, and room for a {@code struct sockaddr_in6} (28 B) or {@code sockaddr_in} (16 B). */
  public static final int IOVEC = 16, SOCKADDR = 32;
  public static final int AF_INET = 2, AF_INET6 = 10, MSG_TRUNC = 0x20;
  /** A slot holds the largest valid datagram, a prefix and 4 KiB, and more: a longer one is dropped like with Java's receive. */
  public static final int SLOT = DATAGRAM_PREFIX_SIZE + MAX_DATAGRAM_SIZE + 8;

  // Receive area: BATCH headers, iovecs, addresses and slots. Send area: BATCH headers, 2 iovecs each,
  // addresses, prefixes and slots for the payloads that can't be pointed to.
  public static final int RX_IOV = BATCH * MMSGHDR, RX_NAME = RX_IOV + BATCH * IOVEC, RX_SLOT = RX_NAME + BATCH * SOCKADDR;
  public static final int TX_IOV = BATCH * MMSGHDR, TX_NAME = TX_IOV + BATCH * 2 * IOVEC, TX_PREFIX = TX_NAME + BATCH * SOCKADDR,
                   TX_SLOT = TX_PREFIX + BATCH * 8;

  protected final int fd;
  /** Native byte order for the C structures, network order for ports and addresses. */
  protected final ByteBuffer rx, rxNet, tx, txNet, txCopy;
  protected final long rxAddress, txAddress;
  /** The receive slots, as given to the relay. */
  protected final ByteBuffer[] slots = new ByteBuffer[BATCH];
  /** Socket family, from the received addresses: the one to send with. */
  protected int family = AF_INET6;
  /** Queued sends. */
  protected int pending;
  /** The slot the relay is handling, and its address: a send of it points there instead of copying. */
  protected ByteBuffer receiving;
  protected long receivingAddress;

  BatchedDatagrams(StreamRelay relay, DatagramChannel channel) throws Throwable {
    super(relay, channel);
    if (!LibClaj.available()) throw LibClaj.error();
    fd = Sockets.descriptor(channel);
    rx = ByteBuffer.allocateDirect(RX_SLOT + BATCH * SLOT).order(ByteOrder.nativeOrder());
    tx = ByteBuffer.allocateDirect(TX_SLOT + BATCH * SLOT).order(ByteOrder.nativeOrder());
    rxNet = rx.duplicate().order(ByteOrder.BIG_ENDIAN);
    txNet = tx.duplicate().order(ByteOrder.BIG_ENDIAN);
    txCopy = tx.duplicate();
    rxAddress = LibClaj.address(rx);
    txAddress = LibClaj.address(tx);
    for (int i=0; i<BATCH; i++) {
      int header = i * MMSGHDR, iov = RX_IOV + i * IOVEC;
      rx.putLong(header + NAME, rxAddress + RX_NAME + i * SOCKADDR);
      rx.putLong(header + IOV, rxAddress + iov);
      rx.putLong(header + IOVLEN, 1);
      rx.putLong(iov, rxAddress + RX_SLOT + i * SLOT);
      rx.putLong(iov + 8, SLOT);
      ByteBuffer slot = rx.duplicate();
      slot.limit(RX_SLOT + (i + 1) * SLOT).position(RX_SLOT + i * SLOT);
      slots[i] = slot.slice(); // Big endian, like Java's receive buffer.
      header = i * MMSGHDR;
      tx.putLong(header + NAME, txAddress + TX_NAME + i * SOCKADDR);
      tx.putLong(header + IOV, txAddress + TX_IOV + i * 2 * IOVEC);
    }
    // Both calls exist and take this descriptor: else ENOSYS (seccomp, old kernel) or EBADF, and Java's UDP.
    int received = LibClaj.recvmmsg(fd, rxAddress, 0), sent = LibClaj.sendmmsg(fd, txAddress, 0);
    if (received < 0 && received != EAGAIN || sent < 0) throw new UnsupportedOperationException("errno " + -Math.min(received, sent));
  }

  @Override
  public String name() {
    return "jni";
  }

  @Override
  public void receive() {
    for (int round=0; round<MAX_PER_TURN / BATCH; round++) {
      if (round > 0) flush(); // Queued sends may point into the slots.
      for (int i=0; i<BATCH; i++) rx.putInt(i * MMSGHDR + NAMELEN, SOCKADDR);
      int n = LibClaj.recvmmsg(fd, rxAddress, BATCH);
      if (n < 0) {
        if (n != EAGAIN) Log.debug("Unable to receive datagrams: errno @", -n);
        return;
      }
      for (int i=0; i<n; i++) handle(i);
      receiving = null;
      if (n < BATCH) return; // Drained: no empty receive to learn it.
    }
  }

  public void handle(int i) {
    int header = i * MMSGHDR, name = RX_NAME + i * SOCKADDR;
    // Truncated: longer than any valid datagram, so dropped by the same checks as with Java's receive.
    int length = (rx.getInt(header + FLAGS) & MSG_TRUNC) != 0 ? SLOT : rx.getInt(header + LENGTH);
    ByteBuffer in = slots[i];
    in.clear();
    in.limit(length);
    long hi, lo;
    int port = rxNet.getShort(name + 2) & 0xffff, af = rx.getShort(name) & 0xffff;
    if (af == AF_INET) {
      hi = 0;
      lo = PeerMap.MAPPED | rxNet.getInt(name + 4) & 0xffffffffL;
    } else if (af == AF_INET6) {
      hi = rxNet.getLong(name + 8);
      lo = rxNet.getLong(name + 16);
    } else return;
    family = af;
    StreamConnection con = peers.get(hi, lo, port);
    InetSocketAddress from = null;
    // Only a HELLO_UDP can associate an unknown address: the address object is made for it only.
    if (con == null && (!StreamRelay.mayBeHello(in) || (from = address(name, af, hi, lo, port)) == null)) return;
    receiving = in;
    receivingAddress = rxAddress + RX_SLOT + i * SLOT;
    relay.datagram(con, from, in);
  }

  public InetSocketAddress address(int name, int af, long hi, long lo, int port) {
    byte[] ip;
    if (af == AF_INET || hi == 0 && lo >>> 32 == 0xffff) {
      ip = new byte[4];
      for (int j=0; j<4; j++) ip[j] = (byte)(lo >>> 24 - 8 * j);
    } else {
      ip = new byte[16];
      for (int j=0; j<8; j++) {
        ip[j] = (byte)(hi >>> 56 - 8 * j);
        ip[8 + j] = (byte)(lo >>> 56 - 8 * j);
      }
    }
    try {
      int scope = af == AF_INET6 ? rx.getInt(name + 24) : 0;
      InetAddress address = scope != 0 && ip.length == 16 ? Inet6Address.getByAddress(null, ip, scope)
                                                          : InetAddress.getByAddress(ip);
      return new InetSocketAddress(address, port);
    } catch (UnknownHostException e) {
      return null; // Not with these lengths.
    }
  }

  @Override
  public void send(StreamConnection to, ByteBuffer payload) {
    queue(to, false, 0, payload);
  }

  @Override
  public void send(StreamConnection to, int prefix, ByteBuffer payload) {
    queue(to, true, prefix, payload);
  }

  /** Queues a datagram, consuming {@code payload} like a channel's send. */
  public void queue(StreamConnection to, boolean prefixed, int prefix, ByteBuffer payload) {
    int length = payload.remaining();
    if (length > MAX_DATAGRAM_SIZE + DATAGRAM_PREFIX_SIZE) return;
    if (pending == BATCH) flush();
    int i = pending++, header = i * MMSGHDR, iov = TX_IOV + i * 2 * IOVEC, end = iov;
    if (prefixed) {
      txNet.putInt(TX_PREFIX + i * 8, prefix);
      tx.putLong(end, txAddress + TX_PREFIX + i * 8);
      tx.putLong(end + 8, DATAGRAM_PREFIX_SIZE);
      end += IOVEC;
    }
    long data;
    if (payload == receiving) {
      data = receivingAddress + payload.position();
      payload.position(payload.limit());
    } else {
      data = txAddress + TX_SLOT + i * SLOT;
      txCopy.limit(TX_SLOT + i * SLOT + length).position(TX_SLOT + i * SLOT);
      txCopy.put(payload);
    }
    tx.putLong(end, data);
    tx.putLong(end + 8, length);
    end += IOVEC;
    tx.putLong(header + IOVLEN, (end - iov) / IOVEC);
    tx.putInt(header + NAMELEN, name(TX_NAME + i * SOCKADDR, to));
  }

  /** Writes the socket address of {@code to} at {@code name}, in the socket's family. @return its length */
  public int name(int name, StreamConnection to) {
    txNet.putShort(name + 2, (short)to.udpPort);
    if (family == AF_INET) {
      tx.putShort(name, (short)AF_INET);
      txNet.putInt(name + 4, (int)to.udpLo);
      tx.putLong(name + 8, 0);
      return 16;
    }
    tx.putShort(name, (short)AF_INET6);
    tx.putInt(name + 4, 0); // flow info
    txNet.putLong(name + 8, to.udpHi);
    txNet.putLong(name + 16, to.udpLo);
    tx.putInt(name + 24, to.udpScope);
    return 28;
  }

  @Override
  public void flush() {
    for (int sent = 0; sent < pending;) {
      int n = LibClaj.sendmmsg(fd, txAddress + (long)sent * MMSGHDR, pending - sent);
      if (n > 0) sent += n; // A prefix of them, never sent again.
      else if (n == EAGAIN || n == 0) break; // The socket buffer is full: the rest is dropped.
      else {
        Log.debug("Unable to send a datagram: errno @", -n);
        sent++; // That one is dropped, the next ones go on.
      }
    }
    pending = 0;
  }
}
