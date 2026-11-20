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

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;

import arc.util.Strings;


/**
 * Hand-written wire format of the CLaJ protocol generations, to not depend on the CLaJ sources.
 * <p>
 * Frame (TCP): {@code u16 length + body}. Datagram (UDP): {@code body}.
 * Body: {@code -2 + framework type...}, {@code -4 + claj packet id...} or a raw payload.
 * <p>
 * The stream relay ({@code stream-notes/protocol.md}) frames stages 1-2 the same way, with {@code u8 type + body}.
 * Then a client's TCP is a raw stream, which the bench frames as {@code u16 length + payload} like the others,
 * and a host's TCP is a mux of {@code u8 type, i32 conId, u16 length} frames.
 */
final class Proto {
  static final byte FRAMEWORK = -2, CLAJ = -4;
  static final byte FW_PING = 0, FW_DISCOVER = 1, FW_KEEPALIVE = 2, FW_REG_UDP = 3, FW_REG_TCP = 4;
  /** Connection id used by 2.4.x servers to broadcast a payload to every client of a room. */
  static final int CON_BROADCAST = 0;
  /** Size of the {@code [-4][id][conId][isTCP]} header wrapping payloads exchanged with the host. */
  static final int WRAP_HEADER = 7;

  /** Kinds of the received claj packets that matter, the others are {@code 0}. */
  static final byte K_PAYLOAD = 1, K_CON_JOIN = 2, K_IDLE = 3, K_LINK = 4, K_ROOM_CLOSED = 5, K_JOIN_DENIED = 6;

  // Stream relay: stage 1-2 message types, then the host mux frame types.
  static final byte S_HELLO = 1, S_HELLO_TCP = 2, S_HELLO_UDP = 3, S_WELCOME = 4, S_JOIN = 17, S_JOIN_ACCEPTED = 18,
                    S_JOIN_DENIED = 19, S_CREATE = 21, S_CREATED = 22, S_CLOSED = 23;
  static final byte M_OPEN = 1, M_DATA = 2, M_CLOSE = 3, M_WINDOW = 4, M_FIN = 5, M_CONTROL = 6, M_DGRAM = 7;
  /** Mux frame header, and the credit of each stream direction. */
  static final int MUX_HEADER = 7, WINDOW = 65536;
  /** Largest datagram payload the stream relay forwards; bigger ones are dropped. */
  static final int MAX_STREAM_DATAGRAM = 4096;

  /** {@code true} for 2.3.x, {@code false} for 2.4.x. Other versions are not compatible. */
  final boolean legacy;
  /** The stream relay, which uses the 2.4.x room packets in its own framing. */
  final boolean stream;
  /** Major version expected by 2.4.x servers in room creation requests. */
  final int version;
  /** Version sent to 2.3.x servers, must not be lower than theirs. */
  final byte[] legacyVersion;
  final byte[] type;
  final byte idPayload, idCreate, idJoin;
  final byte[] kinds = new byte[256];

  private Proto(boolean legacy, boolean stream, int version, String legacyVersion, String type) {
    this.legacy = legacy;
    this.stream = stream;
    this.version = version;
    this.legacyVersion = legacyVersion.getBytes(Strings.utf8);
    this.type = type.getBytes(Strings.ascii);
    if (this.type.length < 1 || this.type.length > 16) throw new IllegalArgumentException("type must be 1-16 chars");

    if (legacy) {
      idPayload = 0; idCreate = 4; idJoin = 7;
      kinds[0] = K_PAYLOAD; kinds[2] = K_CON_JOIN; kinds[3] = K_IDLE; kinds[6] = K_LINK;
    } else {
      idPayload = 2; idCreate = 4; idJoin = 7;
      kinds[0] = K_CON_JOIN; kinds[2] = K_PAYLOAD; kinds[3] = K_IDLE; kinds[6] = K_ROOM_CLOSED;
      kinds[10] = K_JOIN_DENIED; kinds[11] = K_LINK;
    }
  }

  static Proto v23() {
    return new Proto(true, false, -1, "2.3.3", "bench");
  }
  static Proto v24() {
    return new Proto(false, false, 4, "", "bench");
  }
  static Proto stream() {
    return new Proto(false, true, 4, "", "bench");
  }

  /** Kind of the claj packet whose id is at {@code pos+1}. */
  byte kindOf(ByteBuffer body, int pos) {
    return kinds[body.get(pos + 1) & 0xff];
  }

  @Override
  public String toString() {
    return legacy ? "2.3.x" : stream ? "stream" : "2.4.x";
  }

  // region writers (body only, frame length is handled by Peer)

  static void writeRegisterUdp(ByteBuffer b, int conId) {
    b.put(FRAMEWORK).put(FW_REG_UDP).putInt(conId);
  }
  static void writeKeepAlive(ByteBuffer b) {
    b.put(FRAMEWORK).put(FW_KEEPALIVE);
  }

  /** {@code HELLO_TCP} or {@code HELLO_UDP}, the stream relay's registration. */
  static void writeHello(ByteBuffer b, boolean udp, long token) {
    b.put(udp ? S_HELLO_UDP : S_HELLO_TCP).putLong(token);
  }

  /** A host mux frame header. */
  static void writeMux(ByteBuffer b, byte type, int conId, int length) {
    b.put(type).putInt(conId).putShort((short)length);
  }

  void writeCreate(ByteBuffer b) {
    if (stream) b.put(S_CREATE); // the 2.4.x body follows
    else b.put(CLAJ).put(idCreate);
    if (legacy) b.putShort((short)legacyVersion.length).put(legacyVersion);
    else b.putShort((short)0).putInt(version).put((byte)type.length).put(type);
  }

  void writeJoin(ByteBuffer b, long roomId) {
    if (stream) b.put(S_JOIN).putLong(roomId);
    else b.put(CLAJ).put(idJoin).putLong(roomId);
    if (!legacy) b.put((byte)0).putShort((short)-1).put((byte)type.length).put(type);
  }

  void writeWrap(ByteBuffer b, int conId, boolean tcp) {
    b.put(CLAJ).put(idPayload).putInt(conId).put(tcp ? (byte)1 : 0);
  }

  // end region
  // region detection

  /** Parses a version like {@code 2.4.5} or {@code 2.3.3-v8}, from a jar manifest or the command line. */
  static Proto fromVersion(String version) {
    String v = version.trim().replaceFirst("^v", "");
    if (v.equals("stream")) return stream();
    if (v.matches("2\\.3([.-].*)?")) return v23();
    if (v.matches("2\\.4([.-].*)?")) return v24();
    throw new IllegalArgumentException("CLaJ " + version + " is not supported, only 2.3.x and 2.4.x are");
  }

  /**
   * Asks the server using a discovery packet. 2.4.x servers reply {@code [-4][int major version]},
   * 2.3.x ones reply an empty datagram.
   */
  static Proto detect(InetSocketAddress address) throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(1000);
      byte[] req = {FRAMEWORK, FW_DISCOVER}, buf = new byte[64];
      for (int i=0; i<3; i++) {
        socket.send(new DatagramPacket(req, req.length, address));
        DatagramPacket res = new DatagramPacket(buf, buf.length);
        try { socket.receive(res); }
        catch (SocketTimeoutException e) { continue; }
        if (res.getLength() == 0) return v23();
        if (res.getLength() >= 5 && buf[0] == CLAJ) {
          int major = ByteBuffer.wrap(buf, 1, 4).getInt();
          if (major == 4) return v24();
          throw new IOException("CLaJ 2." + major + ".x is not supported, only 2.3.x and 2.4.x are");
        }
        throw new IOException("Unknown discovery response (" + res.getLength() + " bytes)");
      }
    }
    // The stream relay drops unknown datagrams, but greets every TCP connection with a HELLO.
    try (Socket socket = new Socket()) {
      socket.connect(address, 1000);
      socket.setSoTimeout(1000);
      byte[] hello = new byte[3];
      if (socket.getInputStream().readNBytes(hello, 0, 3) == 3 && hello[0] == 0 && hello[1] == 10 && hello[2] == S_HELLO)
        return stream();
    } catch (IOException ignored) {}
    throw new IOException("No discovery response from " + address + ". Use --proto to set it manually.");
  }

  // end region
}
