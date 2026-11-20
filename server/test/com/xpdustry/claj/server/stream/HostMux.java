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
import java.net.Socket;
import java.nio.ByteBuffer;

import arc.net.DcReason;
import arc.util.io.ByteBufferOutput;

import com.xpdustry.claj.common.ClajPackets;
import com.xpdustry.claj.common.packets.Packet;


/**
 * Blocking host side of the mux: frames in, frames out. A minimal helper for tests and the load generator,
 * a real host adapter also tracks the credits of its streams. Use {@link #header} to write frames elsewhere.
 */
public class HostMux implements Closeable {
  public final StreamClient session;
  public final Socket socket;
  public final long roomId;
  protected final DataInputStream in;
  protected final DataOutputStream out;

  public HostMux(StreamClient session, long roomId) throws IOException {
    this.session = session;
    this.socket = session.socket;
    this.roomId = roomId;
    in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
    out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
  }

  /** Writes a frame header in a buffer. */
  public static ByteBuffer header(ByteBuffer out, int type, int id, int length) {
    return out.put((byte)type).putInt(id).putShort((short)length);
  }

  /** One received frame. */
  public static class Frame {
    public final int type, id;
    public final byte[] payload;

    public Frame(int type, int id, byte[] payload) {
      this.type = type;
      this.id = id;
      this.payload = payload;
    }

    /** The reason of a server {@code CLOSE}, read like the 2.x {@code ConnectionClosedPacket}. */
    public DcReason reason() {
      return ClajPackets.Disconnect.get(payload[0] & 0xff).reason;
    }

    /** @return the credit of a {@code WINDOW}. */
    public int credit() {
      return ByteBuffer.wrap(payload).getInt();
    }

    /** @return whether an {@code OPEN} says the client has UDP. */
    public boolean hasUdp() {
      return (payload[0] & FLAG_UDP) != 0;
    }

    @Override
    public String toString() {
      return "Frame(type=" + type + ", id=" + id + ", length=" + payload.length + ")";
    }
  }

  /** Blocks until a whole frame is received. */
  public Frame read() throws IOException {
    int type = in.readUnsignedByte(), id = in.readInt(), length = in.readUnsignedShort();
    byte[] payload = new byte[length];
    in.readFully(payload);
    return new Frame(type, id, payload);
  }

  /** @return the next frame that isn't a {@code WINDOW}. */
  public Frame readSkippingWindows() throws IOException {
    Frame frame;
    do frame = read();
    while (frame.type == WINDOW);
    return frame;
  }

  public synchronized HostMux frame(int type, int id, byte[] payload, int offset, int length) throws IOException {
    out.writeByte(type);
    out.writeInt(id);
    out.writeShort(length);
    out.write(payload, offset, length);
    return this;
  }

  public HostMux data(int id, byte[] payload) throws IOException {
    return frame(DATA, id, payload, 0, payload.length);
  }

  public synchronized HostMux window(int id, int credit) throws IOException {
    out.writeByte(WINDOW);
    out.writeInt(id);
    out.writeShort(WINDOW_PAYLOAD_SIZE);
    out.writeInt(credit);
    return this;
  }

  /** The keep-alive of an idle host, see {@link StreamProtocol#PING}. Writes are synchronized for a pinger thread. */
  public HostMux ping() throws IOException {
    return frame(PING, ROOM, new byte[0], 0, 0);
  }

  public HostMux close(int id) throws IOException {
    return frame(CLOSE, id, new byte[0], 0, 0);
  }

  public HostMux fin(int id) throws IOException {
    return frame(FIN, id, new byte[0], 0, 0);
  }

  /** Sends a stage 2 message for the room, e.g. {@code ROOM_CONFIG}. */
  public HostMux control(int type, Packet packet) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(MAX_CONTROL_SIZE);
    buffer.put((byte)type);
    if (packet != null) packet.write(new ByteBufferOutput(buffer));
    return frame(CONTROL, ROOM, buffer.array(), 0, buffer.position());
  }

  public synchronized HostMux flush() throws IOException {
    out.flush();
    return this;
  }

  @Override
  public void close() throws IOException {
    session.close();
  }
}
