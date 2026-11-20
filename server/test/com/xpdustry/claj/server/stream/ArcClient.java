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

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import arc.net.Client;
import arc.net.Connection;
import arc.net.NetListener;
import arc.net.Server;
import arc.util.io.ByteBufferInput;

import com.xpdustry.claj.common.ClajNet;
import com.xpdustry.claj.common.packets.Packet;
import com.xpdustry.claj.server.ClajRelay;
import com.xpdustry.claj.server.ClajServerSerializer;


/**
 * A 2.x peer of the server's Arc port, for the checks that mix both transports. Packets are read as is. <br>
 * Its {@link #close()} is Arc's, which {@code connect} also calls: its daemon thread stays.
 */
public class ArcClient extends Client implements Closeable {
  public final BlockingQueue<Object> received = new LinkedBlockingQueue<>();

  /** Connects to the Arc port of {@code server}. */
  public ArcClient(ClajRelay server, int timeoutMillis) throws IOException, ReflectiveOperationException {
    super(32768, 32768, new ClajServerSerializer() {
      @Override
      public Packet readClaj(ByteBuffer buffer) {
        Packet packet = ClajNet.newLocalPacket(buffer.get(), false);
        ByteBufferInput input = new ByteBufferInput();
        input.setBuffer(buffer);
        packet.read(input);
        return packet;
      }
    });
    addListener(new NetListener() {
      @Override
      public void received(Connection connection, Object object) {
        received.add(object);
      }
    });
    ServerSocketChannel tcp = (ServerSocketChannel)get(Server.class, server, "serverChannel");
    Object udp = get(Server.class, server, "udp");
    DatagramChannel datagrams = (DatagramChannel)get(udp.getClass(), udp, "datagramChannel");
    start();
    connect(timeoutMillis, InetAddress.getLoopbackAddress(), tcp.socket().getLocalPort(),
            datagrams.socket().getLocalPort());
  }

  /** @return the next packet received of that type, others are skipped. */
  public <T> T read(Class<T> type, int timeoutMillis) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    for (;;) {
      Object object = received.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (object == null) throw new AssertionError("no " + type.getSimpleName() + " received");
      if (type.isInstance(object)) return type.cast(object);
    }
  }

  static Object get(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }
}
