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

import static com.xpdustry.claj.server.stream.StreamProtocol.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

import arc.util.io.ByteBufferInput;
import arc.util.io.ByteBufferOutput;

import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.status.CloseReason;
import com.xpdustry.claj.common.status.RejectReason;


/**
 * Blocking peer side of stages 1 and 2, for clients and hosts.
 * <p>
 * The helper reads exactly the bytes of each message, never more: once a join is accepted,
 * {@link #socket} is a plain socket to the host, ready for any stream protocol. Same for the UDP
 * {@link #datagrams} socket, connected to the relay, whose datagrams now go to the host as they are.
 * <p>
 * Requests are sent one at a time over TCP, each waits for its reply, as the relay does. That follows the
 * UDP client rules of protocol.md by construction: no request is outstanding when a join or a creation is
 * sent, {@code ROOM_CONFIG} goes over TCP before {@code ROOM_CREATE}, and no stage 2 datagram reply can
 * arrive on {@link #datagrams} after an accept.
 */
public class StreamClient implements Closeable {
  /** Delay between two {@code HELLO_UDP}, until the relay answers over TCP. */
  public static final int UDP_RETRY_MILLIS = 100;

  public final Socket socket;
  /** Connected to the relay, {@code null} for a TCP-only connection. */
  public final DatagramSocket datagrams;
  /** Wire protocol version announced by the relay. */
  public final int version;
  /** CLaJ major version sent with room creations, which must be the one of the relay. */
  public int clajVersion = 4;

  protected final DataInputStream in;
  protected final OutputStream out;
  protected final ByteBuffer buffer = ByteBuffer.allocate(LENGTH_SIZE + MAX_REPLY_SIZE);
  protected final ByteBufferInput input = new ByteBufferInput(buffer);
  protected final ByteBufferOutput output = new ByteBufferOutput(buffer);
  /** First byte of the next message when already read, else {@code -1}. */
  protected int peeked = -1;
  protected int lastType;

  /** Connects to the relay and goes through stage 1. */
  public static StreamClient connect(InetSocketAddress relay, boolean udp, int timeoutMillis) throws IOException {
    Socket socket = new Socket();
    try {
      socket.setTcpNoDelay(true);
      socket.connect(relay, timeoutMillis);
      return new StreamClient(socket, udp, timeoutMillis);
    } catch (IOException | RuntimeException e) {
      socket.close();
      throw e;
    }
  }

  /** Joins a room over a TCP-only connection. @return the socket, now a raw stream to the host. */
  public static Socket join(InetSocketAddress relay, long roomId, short password, ClajType type, int timeoutMillis)
  throws IOException {
    StreamClient client = connect(relay, false, timeoutMillis);
    RejectReason reason;
    try {
      reason = client.join(roomId, password, type);
    } catch (IOException | RuntimeException e) {
      // The socket is the caller's only after a successful join.
      client.close();
      throw e;
    }
    if (reason == null) return client.socket;
    client.close();
    throw new IOException("join denied: " + reason);
  }

  /**
   * Goes through stage 1 on a connected socket, e.g. the one of a {@link java.nio.channels.SocketChannel}
   * in blocking mode. The socket timeout is used for every reply.
   */
  public StreamClient(Socket socket, boolean udp, int timeoutMillis) throws IOException {
    this(socket, udp ? new DatagramSocket(new InetSocketAddress(socket.getLocalAddress(), 0)) : null, timeoutMillis);
  }

  /**
   * Same, with a given UDP socket for the association, e.g. the one of a {@link java.nio.channels.DatagramChannel}
   * in blocking mode. It gets connected to the relay, and closed if stage 1 fails.
   * @param udp {@code null} for a TCP-only connection.
   */
  public StreamClient(Socket socket, DatagramSocket udp, int timeoutMillis) throws IOException {
    this.socket = socket;
    datagrams = udp;
    try {
      socket.setSoTimeout(timeoutMillis);
      in = new DataInputStream(socket.getInputStream());
      out = socket.getOutputStream();

      ByteBuffer hello = receive(HELLO);
      version = hello.get() & 0xff;
      if (version != VERSION) throw new ProtocolException("unsupported relay version " + version);
      long token = hello.getLong();
      if (udp == null) {
        start(HELLO_TCP).putLong(token);
        send();
      } else associate(token, timeoutMillis);
      int flags = receive(WELCOME).get();
      if ((flags & FLAG_UDP) != (udp != null ? FLAG_UDP : 0)) throw new ProtocolException("unexpected flags " + flags);
    } catch (IOException | RuntimeException e) {
      if (udp != null) udp.close();
      throw e;
    }
  }

  /** Sends the token over UDP until the {@code WELCOME} starts arriving over TCP. */
  void associate(long token, int timeoutMillis) throws IOException {
    datagrams.connect(socket.getRemoteSocketAddress());
    byte[] hello = ByteBuffer.allocate(1 + Long.BYTES).put((byte)HELLO_UDP).putLong(token).array();
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    socket.setSoTimeout(UDP_RETRY_MILLIS);
    while (true) {
      datagrams.send(new DatagramPacket(hello, hello.length));
      try {
        // Only the first byte waits with the short timeout, the rest of the message comes with it.
        int first = in.read();
        if (first < 0) throw new EOFException("relay closed the connection");
        peeked = first;
        break;
      } catch (SocketTimeoutException e) {
        if (System.nanoTime() - deadline > 0) throw e;
      }
    }
    socket.setSoTimeout(timeoutMillis);
  }

  // region requests

  /** @return the CLaJ major version of the relay. */
  public int serverVersion() throws IOException {
    start(VERSION_REQUEST);
    send();
    ServerInfoPacket packet = new ServerInfoPacket();
    packet.read(new ByteBufferInput(receive(SERVER_INFO)));
    return packet.version;
  }

  /** @return the room info, or {@code null} if the room doesn't exist. */
  public RoomInfoPacket roomInfo(long roomId) throws IOException {
    RoomInfoRequestPacket request = new RoomInfoRequestPacket();
    request.roomId = roomId;
    request(ROOM_INFO_REQUEST, request);
    ByteBuffer reply = receive(ROOM_INFO, ROOM_INFO_DENIED);
    if (reply == null) return null;
    RoomInfoPacket packet = new RoomInfoPacket();
    packet.read(new ByteBufferInput(reply));
    return packet;
  }

  public RoomListPacket roomList(ClajType type) throws IOException {
    RoomListRequestPacket request = new RoomListRequestPacket();
    request.type = type;
    request(ROOM_LIST_REQUEST, request);
    RoomListPacket packet = new RoomListPacket();
    packet.read(new ByteBufferInput(receive(ROOM_LIST)));
    return packet;
  }

  /**
   * Asks to join a room. On success, {@link #socket} is the stream to the host. On refusal, the
   * connection stays in stage 2 and another request can be sent.
   * @param password {@code -1} for none.
   * @return {@code null} if accepted, else the reason.
   */
  public RejectReason join(long roomId, short password, ClajType type) throws IOException {
    RoomJoinRequestPacket request = new RoomJoinRequestPacket();
    request.roomId = roomId;
    request.withPassword = password != -1;
    request.password = password;
    request.type = type;
    request(JOIN_REQUEST, request);
    ByteBuffer reply = receive(JOIN_ACCEPTED, JOIN_DENIED);
    if (reply != null) return null;
    RoomJoinDeniedPacket denied = new RoomJoinDeniedPacket();
    denied.read(new ByteBufferInput(lastBody()));
    return denied.reason;
  }

  /**
   * Creates a room, configured before anyone can join it.
   * @param config {@code null} for the defaults.
   * @return the host side of the room mux.
   * @throws Refused if the relay refused, the connection then stays in stage 2.
   */
  public HostMux createRoom(ClajType type, RoomConfigPacket config) throws IOException {
    if (config != null) request(ROOM_CONFIG, config);
    RoomCreationRequestPacket request = new RoomCreationRequestPacket();
    request.version = clajVersion;
    request.type = type;
    request(ROOM_CREATE, request);
    ByteBuffer reply = receive(ROOM_CREATED, ROOM_CLOSED);
    if (reply == null) {
      RoomClosedPacket closed = new RoomClosedPacket();
      closed.read(new ByteBufferInput(lastBody()));
      throw new Refused(closed.reason);
    }
    RoomLinkPacket link = new RoomLinkPacket();
    link.read(new ByteBufferInput(reply));
    return new HostMux(this, link.roomId);
  }

  @Override
  public void close() throws IOException {
    if (datagrams != null) datagrams.close();
    socket.close();
  }

  /** A refused room creation. */
  public static class Refused extends IOException {
    public final CloseReason reason;

    public Refused(CloseReason reason) {
      super("room creation refused: " + reason);
      this.reason = reason;
    }
  }

  // end region
  // region messages

  protected ByteBuffer start(int type) {
    buffer.clear();
    buffer.position(LENGTH_SIZE);
    buffer.put((byte)type);
    return buffer;
  }

  protected void request(int type, Packet packet) throws IOException {
    start(type);
    packet.write(output);
    send();
  }

  /** Sends the message in {@link #buffer} with one write. */
  protected void send() throws IOException {
    int length = buffer.position() - LENGTH_SIZE;
    buffer.putShort(0, (short)length);
    out.write(buffer.array(), 0, buffer.position());
    out.flush();
  }

  /** @return the body of the next message, which must be of that type. */
  protected ByteBuffer receive(int type) throws IOException {
    ByteBuffer body = receive(type, -1);
    if (body == null) throw new ProtocolException("unexpected message " + lastType);
    return body;
  }

  /** @return the body if the next message is {@code success}, {@code null} if it is {@code failure}. */
  protected ByteBuffer receive(int success, int failure) throws IOException {
    int first = peeked >= 0 ? peeked : in.read();
    peeked = -1;
    if (first < 0) throw new EOFException("relay closed the connection");
    int length = first << 8 | in.readUnsignedByte();
    if (length < 1) throw new ProtocolException("empty message");
    buffer.clear();
    in.readFully(buffer.array(), 0, length);
    buffer.limit(length);
    lastType = buffer.get() & 0xff;
    if (lastType == success) return buffer;
    if (lastType == failure) return null;
    throw new ProtocolException("unexpected message " + lastType + ", expected " + success);
  }

  /** @return the body of the last message. */
  protected ByteBuffer lastBody() {
    buffer.position(1);
    return buffer;
  }

  // end region
}
