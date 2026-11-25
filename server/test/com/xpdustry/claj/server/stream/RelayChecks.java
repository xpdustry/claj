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
import java.lang.reflect.Field;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;

import arc.ApplicationListener;
import arc.Events;
import arc.net.DcReason;
import arc.util.Threads;

import com.xpdustry.claj.common.ClajPackets;
import com.xpdustry.claj.common.packets.*;
import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.status.CloseReason;
import com.xpdustry.claj.common.status.RejectReason;
import com.xpdustry.claj.server.*;


/**
 * Socket-level checks of the relay, without a test framework so they run as is on Java 8 and 25. <br>
 * Usage: {@code RelayChecks [name...]}, every check by default.
 */
public final class RelayChecks {
  static final ClajType TYPE = new ClajType("checks");
  static final int TIMEOUT = 3000;
  static final LinkedHashMap<String, Check> checks = new LinkedHashMap<>();

  interface Check { void run() throws Exception; }

  static ServerApplication app;
  static ClajRelay server;
  static ClajControl control;
  static StreamRelay relay;
  static InetSocketAddress address;

  public static void main(String[] args) throws Exception {
    boot();
    register();
    List<String> names = args.length == 0 ? new ArrayList<>(checks.keySet()) : Arrays.asList(args);
    int failed = 0;
    System.out.println("Java " + System.getProperty("java.version"));
    for (String name : names) {
      long start = System.nanoTime();
      try {
        config();
        checks.get(name).run();
        System.out.printf(Locale.ROOT, "PASS %-28s %6.0f ms%n", name, (System.nanoTime() - start) / 1e6);
      } catch (Throwable e) {
        failed++;
        System.out.printf(Locale.ROOT, "FAIL %-28s %s%n", name, e);
        e.printStackTrace(System.out);
      } finally {
        stop();
      }
    }
    System.out.println(names.size() - failed + "/" + names.size() + " checks passed");
    System.exit(failed == 0 ? 0 : 1);
  }

  static void register() {
    // region stage 1
    checks.put("hello", () -> {
      start();
      try (Socket socket = raw()) {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] hello = message(in, HELLO);
        require(hello.length == 9 && hello[0] == VERSION, "HELLO carries version and token");
        long token = ByteBuffer.wrap(hello, 1, 8).getLong();
        require(token != 0, "token is set");
        send(socket, HELLO_TCP, ByteBuffer.allocate(8).putLong(token).array());
        require(message(in, WELCOME)[0] == 0, "TCP-only welcome");
        send(socket, VERSION_REQUEST, new byte[0]);
        require(ByteBuffer.wrap(message(in, SERVER_INFO)).getInt() == 4, "server version");
      }
    });
    checks.put("hello-garbage", () -> {
      start();
      try (Socket socket = raw()) {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        message(in, HELLO);
        socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes("ASCII"));
        requireClosed(socket, "garbage closes");
      }
      try (Socket socket = raw()) {
        message(new DataInputStream(socket.getInputStream()), HELLO);
        socket.getOutputStream().write(new byte[]{0, 0});
        requireClosed(socket, "zero length closes");
      }
    });
    checks.put("hello-wrong-token", () -> {
      start();
      try (Socket a = raw(); Socket b = raw()) {
        long tokenA = token(a);
        token(b);
        // B presents A's token: tokens are bound to their connection.
        send(b, HELLO_TCP, ByteBuffer.allocate(8).putLong(tokenA).array());
        requireClosed(b, "foreign token closes");
        send(a, HELLO_TCP, ByteBuffer.allocate(8).putLong(tokenA + 1).array());
        requireClosed(a, "wrong token closes");
      }
    });
    checks.put("hello-slowloris", () -> {
      start();
      try (Socket idle = raw(); Socket partial = raw()) {
        token(partial);
        partial.getOutputStream().write(new byte[]{0, 9, HELLO_TCP, 1, 2}); // Never finished.
        long start = System.nanoTime();
        requireClosed(idle, "silent connection closed");
        requireClosed(partial, "partial hello closed");
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        require(elapsed < 2000, "closed by the hello timeout, " + elapsed + " ms");
      }
    });
    checks.put("hello-expired-token", () -> {
      start();
      try (Socket socket = raw(); DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
        long token = token(socket);
        Thread.sleep(ClajConfig.registerTimeout.get() * 1000L + 300);
        require(relay.tokens.size == 0, "token expired with the connection");
        udp.send(helloUdp(token));
        requireClosed(socket, "a late UDP hello doesn't revive it");
      }
    });
    checks.put("hello-udp", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        require(client.datagrams != null && client.datagrams.isConnected(), "UDP associated");
        require(client.serverVersion() == 4, "stage 2 after UDP hello");
      }
    });
    checks.put("hello-udp-duplicate", () -> {
      start();
      try (Socket socket = raw(); DatagramSocket first = new DatagramSocket(0, InetAddress.getLoopbackAddress());
           DatagramSocket second = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        long token = token(socket);
        first.send(helloUdp(token));
        require(message(in, WELCOME)[0] == FLAG_UDP, "first datagram associates");
        second.send(helloUdp(token));
        first.send(helloUdp(token));
        send(socket, VERSION_REQUEST, new byte[0]);
        // A second WELCOME would come before this reply.
        require(ByteBuffer.wrap(message(in, SERVER_INFO)).getInt() == 4, "spent token ignored");
        require(relay.udp.peers.size == 1, "one association only, got " + relay.udp.peers.size);
      }
    });
    checks.put("hello-udp-other-address", () -> {
      start();
      try (Socket socket = raw(); DatagramSocket other = new DatagramSocket(0, InetAddress.getByName("127.0.0.2"))) {
        long token = token(socket);
        other.send(helloUdp(token));
        Thread.sleep(200);
        require(relay.udp.peers.size == 0, "token from another address ignored");
        send(socket, HELLO_TCP, ByteBuffer.allocate(8).putLong(token).array());
        require(message(new DataInputStream(socket.getInputStream()), WELCOME)[0] == 0, "TCP confirm still works");
      }
    });
    // end region
    // region stage 2
    checks.put("join-retry", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        require(client.join(1234, (short)-1, TYPE) == RejectReason.roomNotFound, "unknown room denied");
        require(client.roomInfo(1234) == null, "unknown room info denied");
        RoomConfigPacket config = new RoomConfigPacket();
        config.isProtected = true;
        config.password = 4321;
        config.isPublic = true;
        try (HostMux host = host(config)) {
          require(client.join(host.roomId, (short)-1, TYPE) == RejectReason.passwordRequired, "password required");
          require(client.join(host.roomId, (short)1, TYPE) == RejectReason.invalidPassword, "invalid password");
          RoomInfoPacket info = client.roomInfo(host.roomId);
          require(info != null && info.isProtected && info.clients == 0 && info.type.equals(TYPE), "room info");
          require(client.join(host.roomId, (short)4321, TYPE) == null, "retry after denied joins");
          HostMux.Frame open = host.read();
          require(open.type == OPEN && open.id == 1 && !open.hasUdp() && open.payload.length == OPEN_SIZE, "OPEN");
          require(host.read().credit() == WINDOW_SIZE, "initial downstream window");
        }
      }
    });
    checks.put("join-denied-limit", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        // A denied request counts as any other toward max-requests.
        for (int i = 0; i < ClajConfig.maxRequests.get(); i++)
          require(client.join(i + 1, (short)-1, TYPE) == RejectReason.roomNotFound, "denied " + i);
        requireClosed(client.socket, "closed after " + ClajConfig.maxRequests.get() + " requests");
      }
    });
    checks.put("join-incompatible-full", () -> {
      start();
      RoomConfigPacket config = new RoomConfigPacket();
      config.maxClients = 1;
      try (HostMux host = host(config);
           StreamClient a = StreamClient.connect(address, false, TIMEOUT);
           StreamClient b = StreamClient.connect(address, false, TIMEOUT)) {
        require(a.join(host.roomId, (short)-1, new ClajType("other")) == RejectReason.incompatible, "type mismatch");
        require(a.join(host.roomId, (short)-1, TYPE) == null, "first join");
        require(b.join(host.roomId, (short)-1, TYPE) == RejectReason.roomFull, "room full");
        int id = host.read().id;
        host.read();
        host.window(id, 1).flush();
        a.close();
        // An EOF is a half-close: the host decides, a game host closes the stream.
        require(host.read().type == FIN, "FIN after the client left");
        host.close(id).flush();
        Thread.sleep(100); // The CLOSE and the join travel on different sockets.
        require(b.join(host.roomId, (short)-1, TYPE) == null, "capacity released");
        require(host.read().id == 2, "conId never reused");
      }
    });
    checks.put("join-pipelined", () -> {
      start();
      try (HostMux host = host(null); Socket socket = raw()) {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        long token = token(socket);
        send(socket, HELLO_TCP, ByteBuffer.allocate(8).putLong(token).array());
        message(in, WELCOME);
        // A join followed by stream bytes in the same write: the relay can't know where the stream starts.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(frame(JOIN_REQUEST, joinBody(host.roomId)));
        bytes.write("hello".getBytes("ASCII"));
        socket.getOutputStream().write(bytes.toByteArray());
        byte[] denied = message(in, JOIN_DENIED);
        require(denied[8] == RejectReason.error.ordinal(), "pipelined join denied with error");
        requireClosed(socket, "then closed");
        require(room(host.roomId).clients.size == 0, "no stream opened");
      }
    });
    checks.put("request-limit", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        for (int i = 0; i < ClajConfig.maxRequests.get(); i++) require(client.serverVersion() == 4, "request " + i);
        try {
          client.serverVersion();
          throw new AssertionError("request past the limit answered");
        } catch (IOException expected) {}
      }
    });
    checks.put("udp-control-reply", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        client.datagrams.send(helloUdp(0)); // A late HELLO_UDP retransmit is ignored.
        udpRequest(client, VERSION_REQUEST, new byte[0]);
        ByteBuffer reply = udpReply(client, SERVER_INFO);
        require(reply.getInt() == 4 && !reply.hasRemaining(), "SERVER_INFO over UDP");
        udpRequest(client, ROOM_INFO_REQUEST, new byte[8]);
        udpReply(client, ROOM_INFO_DENIED);
        require(client.socket.getInputStream().available() == 0, "nothing over TCP");
        require(client.serverVersion() == 4, "TCP requests still work");
      }
    });
    checks.put("udp-control-join", () -> {
      start();
      try (HostMux host = host(null); StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        echoHost(host);
        udpRequest(client, JOIN_REQUEST, joinBody(host.roomId + 1));
        udpReply(client, JOIN_DENIED);
        udpRequest(client, JOIN_REQUEST, joinBody(host.roomId));
        DataInputStream in = new DataInputStream(client.socket.getInputStream());
        require(ByteBuffer.wrap(message(in, JOIN_ACCEPTED)).getLong() == host.roomId, "JOIN_ACCEPTED over TCP");
        client.socket.getOutputStream().write(new byte[]{1, 2, 3});
        byte[] echo = new byte[3];
        in.readFully(echo);
        require(echo[2] == 3, "TCP is raw after the accept");
        client.datagrams.setSoTimeout(200);
        try {
          client.datagrams.receive(new DatagramPacket(new byte[64], 64));
          throw new AssertionError("no reply over UDP for an accept");
        } catch (SocketTimeoutException expected) {}
      }
    });
    checks.put("udp-control-create", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        RoomCreationRequestPacket request = new RoomCreationRequestPacket();
        request.version = 4;
        request.type = TYPE;
        ByteBuffer body = ByteBuffer.allocate(64);
        request.write(new arc.util.io.ByteBufferOutput(body));
        udpRequest(client, ROOM_CREATE, Arrays.copyOf(body.array(), body.position()));
        long roomId = ByteBuffer.wrap(message(new DataInputStream(client.socket.getInputStream()), ROOM_CREATED)).getLong();
        require(null != room(roomId), "ROOM_CREATED over TCP, room open");
      }
    });
    checks.put("udp-control-list-truncated", () -> {
      start();
      RoomConfigPacket visible = new RoomConfigPacket();
      visible.isPublic = true;
      List<HostMux> hosts = new ArrayList<>();
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        RoomStatePacket state = new RoomStatePacket();
        state.state = ByteBuffer.wrap(new byte[RoomStatePacket.MAX_BUFF_SIZE]);
        for (int i = 0; i < 4; i++) {
          HostMux host = host(visible);
          hosts.add(host);
          state.state.rewind();
          host.control(ROOM_STATE, state).flush();
        }
        Thread.sleep(100);
        require(client.roomList(TYPE).states.size == 4, "TCP lists the 4 rooms");
        RoomListRequestPacket request = new RoomListRequestPacket();
        request.type = TYPE;
        ByteBuffer body = ByteBuffer.allocate(64);
        request.write(new arc.util.io.ByteBufferOutput(body));
        udpRequest(client, ROOM_LIST_REQUEST, Arrays.copyOf(body.array(), body.position()));
        ByteBuffer reply = udpReply(client, ROOM_LIST);
        require(reply.limit() <= MAX_DATAGRAM_SIZE, "one datagram, " + reply.limit() + " B");
        RoomListPacket list = new RoomListPacket();
        list.read(new arc.util.io.ByteBufferInput(reply));
        require(list.states.size == 3, "UDP lists the 3 rooms that fit, got " + list.states.size);
      } finally {
        for (HostMux host : hosts) host.close();
      }
    });
    checks.put("udp-control-pipelined", () -> {
      start();
      try (HostMux host = host(null); StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        client.socket.getOutputStream().write(0); // Half a TCP request.
        Thread.sleep(100);
        udpRequest(client, JOIN_REQUEST, joinBody(host.roomId));
        RoomJoinDeniedPacket denied = new RoomJoinDeniedPacket();
        denied.read(new arc.util.io.ByteBufferInput(udpReply(client, JOIN_DENIED)));
        require(denied.reason == RejectReason.error, "denied with error");
        requireClosed(client.socket, "lingers like a pipelined TCP join");
      }
    });
    checks.put("udp-control-malformed", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        udpRequest(client, 99, new byte[0]);
        requireClosed(client.socket, "unknown request type over UDP");
      }
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        udpRequest(client, VERSION_REQUEST, new byte[1]);
        udpReply(client, SERVER_INFO);
        requireClosed(client.socket, "trailing bytes over UDP");
      }
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        udpRequest(client, ROOM_INFO_REQUEST, new byte[MAX_REQUEST_SIZE]);
        requireClosed(client.socket, "oversized request over UDP");
      }
    });
    checks.put("udp-transition-reads-tcp-first", () -> {
      // The review's selector order, driven by hand: the UDP key before the TCP one, an earlier TCP
      // request still in the kernel. It must be answered first, not become the start of the mux.
      try (Socket tcp = new Socket(); DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
        StreamConnection con = drivenControl(tcp, udp);
        tcp.getOutputStream().write(frame(VERSION_REQUEST, new byte[0]));
        udp.send(new DatagramPacket(createBody(ROOM_CREATE), createBody(ROOM_CREATE).length, address));
        Thread.sleep(20);
        relay.receive();
        relay.flushDirty();
        DataInputStream in = new DataInputStream(tcp.getInputStream());
        require(ByteBuffer.wrap(message(in, SERVER_INFO)).getInt() == 4, "the TCP request is answered first");
        message(in, ROOM_CREATED);
        tcp.getOutputStream().write(ByteBuffer.allocate(8).put((byte)CONTROL).putInt(0).putShort((short)1)
                                              .put((byte)ROOM_CLOSE).array());
        Thread.sleep(20);
        relay.read(con);
        require(roomCount() == 0, "the next bytes are a valid mux frame, here ROOM_CLOSE");
      }
    });
    checks.put("udp-control-config", () -> {
      RoomConfigPacket config = new RoomConfigPacket();
      config.isProtected = true;
      config.password = 123;
      ByteBuffer body = ByteBuffer.allocate(64);
      config.write(new arc.util.io.ByteBufferOutput(body));
      byte[] configBody = Arrays.copyOf(body.array(), body.position());
      // Over UDP, refused out loud: nothing would order it before a ROOM_CREATE over UDP.
      start();
      try (StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        udpRequest(client, ROOM_CONFIG, configBody);
        RoomClosedPacket closed = new RoomClosedPacket();
        closed.read(new arc.util.io.ByteBufferInput(udpReply(client, ROOM_CLOSED)));
        require(closed.reason == CloseReason.error, "ROOM_CLOSED error");
        require(client.createRoom(TYPE, null) != null, "still in stage 2");
        require(!relay.rooms().first().isProtected, "nothing applied");
      }
      stop();
      // Over TCP, then a UDP ROOM_CREATE handled before the TCP key: the configuration still comes first.
      try (Socket tcp = new Socket(); DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
        drivenControl(tcp, udp);
        tcp.getOutputStream().write(frame(ROOM_CONFIG, configBody));
        udp.send(new DatagramPacket(createBody(ROOM_CREATE), createBody(ROOM_CREATE).length, address));
        Thread.sleep(20);
        relay.receive();
        relay.flushDirty();
        long roomId = ByteBuffer.wrap(message(new DataInputStream(tcp.getInputStream()), ROOM_CREATED)).getLong();
        StreamRoom room = room(roomId);
        require(room.isProtected && room.password == 123, "the TCP ROOM_CONFIG applies to the UDP creation");
      }
    });
    checks.put("control-deadline", () -> {
      start();
      try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        long start = System.nanoTime();
        requireClosed(client.socket, "stage 2 has an absolute deadline");
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        require(elapsed > ClajConfig.controlTimeout.get() * 1000 - ClajConfig.registerTimeout.get() * 1000 - 200,
                "not before, " + elapsed + " ms");
      }
    });
    checks.put("room-list", () -> {
      start();
      RoomConfigPacket visible = new RoomConfigPacket();
      visible.isPublic = true;
      try (HostMux shown = host(visible); HostMux hidden = host(null);
           StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        RoomStatePacket state = new RoomStatePacket();
        state.state = ByteBuffer.wrap("state".getBytes("ASCII"));
        shown.control(ROOM_STATE, state).flush();
        Thread.sleep(100);
        RoomListPacket list = client.roomList(TYPE);
        require(list.states.size == 1 && list.states.containsKey(shown.roomId), "only public rooms");
        require(Arrays.equals(Arrays.copyOf(list.states.get(shown.roomId).array(), 5), "state".getBytes("ASCII")), "state");
        require(client.roomList(new ClajType("other")).states.size == 0, "other type");
        require(hidden.roomId != 0, "hidden room exists");
      }
    });
    checks.put("room-create-refused", () -> {
      config();
      ClajConfig.maxRooms.set(1);
      start();
      // In ClajRelay.onRoomCreate's order: the room count, then the version.
      try (StreamClient second = StreamClient.connect(address, false, TIMEOUT)) {
        second.clajVersion = 3;
        try {
          second.createRoom(TYPE, null);
          throw new AssertionError("outdated client accepted");
        } catch (StreamClient.Refused e) {
          require(e.reason == CloseReason.outdatedClient, "version check: " + e.reason);
        }
        second.clajVersion = 4;
        try (HostMux host = host(null)) {
          second.createRoom(TYPE, null);
          throw new AssertionError("second room created");
        } catch (StreamClient.Refused e) {
          require(e.reason == CloseReason.serverFull, "max rooms: " + e.reason);
        }
        require(second.serverVersion() == 4, "still in stage 2");
      }
    });
    checks.put("room-close", () -> {
      start();
      try (HostMux host = host(null); Socket client = StreamClient.join(address, 0, (short)-1, TYPE, TIMEOUT)) {
        require(client == null, "unreachable");
      } catch (IOException e) {
        require(e.getMessage().contains("roomNotFound"), "join helper reports the reason: " + e.getMessage());
      }
      try (HostMux host = host(null)) {
        Socket client = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        host.read(); host.read();
        host.control(ROOM_CLOSE, null).flush();
        requireClosed(client, "client closed with the room");
        HostMux.Frame closed = host.read();
        require(closed.type == CONTROL && closed.payload[0] == ROOM_CLOSED &&
                closed.payload[1] == CloseReason.closed.ordinal(), "host told why");
        requireClosed(host.socket, "host closed");
        require(roomCount() == 0, "room removed");
      }
    });
    // end region
    // region stage 3
    checks.put("plain-socket-echo", () -> {
      start();
      try (HostMux host = host(null)) {
        Thread echo = echoHost(host);
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        // An unmodified blocking line client, using the socket as any other one.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)) {
          for (int i = 0; i < 100; i++) {
            writer.println("line " + i + " " + new String(new char[i * 50]).replace('\0', 'x'));
            require(reader.readLine().equals("line " + i + " " + new String(new char[i * 50]).replace('\0', 'x')), "echo " + i);
          }
        }
        echo.interrupt();
      }
    });
    checks.put("stream-integrity", () -> {
      start();
      try (HostMux host = host(null)) {
        Thread echo = echoHost(host);
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        byte[] data = new byte[4 << 20];
        new Random(42).nextBytes(data);
        Thread writer = new Thread(() -> {
          try { socket.getOutputStream().write(data); }
          catch (IOException e) { throw new UncheckedIOException(e); }
        });
        writer.start();
        byte[] back = new byte[data.length];
        new DataInputStream(socket.getInputStream()).readFully(back);
        writer.join();
        require(Arrays.equals(data, back), "4 MiB echoed intact through 64 KiB windows");
        socket.close();
        echo.interrupt();
      }
    });
    checks.put("close-after-data", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int id = host.read().id;
        require(host.read().credit() == WINDOW_SIZE, "window");
        byte[] data = new byte[WINDOW_SIZE];
        new Random(1).nextBytes(data);
        // A whole window and CLOSE in one write: like a kick message before a disconnect.
        host.frame(DATA, id, data, 0, 65535).frame(DATA, id, data, 65535, 1).close(id).flush();
        byte[] back = new byte[data.length];
        new DataInputStream(socket.getInputStream()).readFully(back);
        require(Arrays.equals(data, back), "every byte before CLOSE delivered");
        require(socket.getInputStream().read() == -1, "then EOF");
        socket.close();
        // No CLOSE back for a host CLOSE, and the id is retired: late frames are ignored.
        host.data(id, new byte[]{1}).window(id, 10).flush();
        Socket next = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        // Credit returned before the CLOSE was read may still come, nothing else.
        HostMux.Frame open = host.readSkippingWindows();
        require(open.type == OPEN && open.id == id + 1, "no frame for the closed stream, got " + open);
        next.close();
      }
    });
    checks.put("client-abort", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int id = host.read().id;
        host.read();
        host.window(id, WINDOW_SIZE).flush();
        socket.getOutputStream().write(new byte[1000]);
        socket.setSoLinger(true, 0);
        socket.close(); // RST
        int data = 0;
        HostMux.Frame frame;
        while ((frame = host.read()).type == DATA) data += frame.payload.length;
        require(frame.type == CLOSE || frame.type == FIN, "end of stream after its data, got " + frame);
        require(data <= 1000, "no extra data");
        host.data(id, new byte[]{1}).flush(); // Late frame, ignored.
        Thread.sleep(100);
        require(roomCount() == 1, "room survives late frames");
      }
    });
    checks.put("idle-timeout-client", () -> {
      idleConfig();
      start();
      try (HostMux host = host(null)) {
        pinger(host);
        // Sent a byte but has no credit: waiting for the host, not idle.
        Socket waiting = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        waiting.getOutputStream().write(1);
        int waitingId = host.read().id;
        host.read();
        // Silent with credit: closed, and the host is told why, like ConnectionClosedPacket in 2.x.
        Socket silent = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int silentId = host.read().id;
        host.read();
        host.window(silentId, WINDOW_SIZE).flush();
        HostMux.Frame close = host.read();
        require(close.type == CLOSE && close.id == silentId && close.reason() == DcReason.timeout,
                "CLOSE timeout for the silent client, got " + close + " " + Arrays.toString(close.payload));
        requireClosed(silent, "silent client");
        Thread.sleep(idleMillis() * 2);
        require(room(host.roomId).streams.get(waitingId) != null, "a client waiting for credit stays");
        // A reset is an error, not a timeout. Noticed once the stream has credit (open question 5).
        host.window(waitingId, WINDOW_SIZE).flush();
        HostMux.Frame data = host.read();
        require(data.type == DATA && data.id == waitingId && data.payload.length == 1, "the waiting byte, got " + data);
        waiting.setSoLinger(true, 0);
        waiting.close();
        close = host.read();
        require(close.type == CLOSE && close.id == waitingId && close.reason() == DcReason.error,
                "CLOSE error after a reset, got " + close + " " + Arrays.toString(close.payload));
      }
    });
    checks.put("idle-timeout-datagrams", () -> {
      idleConfig();
      start();
      try (HostMux host = host(null); StreamClient client = StreamClient.connect(address, true, TIMEOUT)) {
        pinger(host);
        require(client.join(host.roomId, (short)-1, TYPE) == null, "joined");
        int id = host.read().id;
        host.read();
        host.window(id, WINDOW_SIZE).flush();
        long end = System.nanoTime() + idleMillis() * 3_000_000L;
        int datagrams = 0;
        host.socket.setSoTimeout(100);
        while (System.nanoTime() - end < 0) {
          client.datagrams.send(new DatagramPacket(new byte[]{1}, 1));
          try {
            while (true) {
              HostMux.Frame frame = host.read();
              require(frame.type == DGRAM, "only datagrams while it sends them, got " + frame);
              datagrams++;
            }
          } catch (SocketTimeoutException next) {}
        }
        host.socket.setSoTimeout(TIMEOUT);
        require(datagrams > 0 && room(host.roomId).streams.get(id) != null, "datagrams keep it alive");
        HostMux.Frame close;
        do close = host.read(); while (close.type == DGRAM);
        require(close.type == CLOSE && close.reason() == DcReason.timeout, "then the timeout, got " + close);
      }
    });
    checks.put("idle-timeout-host", () -> {
      idleConfig();
      start();
      try (HostMux pinging = host(null); HostMux silent = host(null)) {
        Socket client = StreamClient.join(address, silent.roomId, (short)-1, TYPE, TIMEOUT);
        long end = System.nanoTime() + idleMillis() * 3_000_000L;
        while (System.nanoTime() - end < 0) {
          pinging.ping().flush();
          Thread.sleep(idleMillis() / 4);
        }
        require(null != room(pinging.roomId), "a host sending PING stays");
        require(null == room(silent.roomId), "a silent host's room is closed");
        requireClosed(client, "and its clients with it");
        requireClosed(silent.socket, "the silent host");
      }
    });
    checks.put("config-clajconfig", () -> {
      // The relay's settings are ClajConfig fields, with the keys of the 2.x config.json: set, saved, reloaded.
      config();
      for (String key : new String[]{"stream-port", "memory-budget", "control-timeout", "linger-timeout", "idle-timeout",
                                     "keep-alive", "max-requests", "slice-size", "udp-batching", "client-rate",
                                     "host-rate"})
        require(ClajConfig.get(key) != null, "field " + key);
      onMain(() -> {
        control.handleCommand("config slice-size set 8192");
        control.handleCommand("config idle-timeout set 7.5");
      });
      require(ClajConfig.sliceSize.get() == 8192 && ClajConfig.idleTimeout.get() == 7.5f, "set by the console");
      ClajConfig.save();
      ClajConfig.sliceSize.set(4096);
      onMain(() -> control.handleCommand("config reload"));
      require(ClajConfig.sliceSize.get() == 8192, "reloaded from " + ClajConfig.fileName);
      start();
      require(relay.sliceSize == 8192, "the relay reads it at start");
      stop();
      boolean refused = false;
      try { ClajConfig.sliceSize.set(0); }
      catch (IllegalArgumentException e) { refused = true; }
      require(refused && ClajConfig.sliceSize.get() == 8192, "an invalid slice size is refused, see config-validation");
      config();
      ClajConfig.save();
    });
    checks.put("keepalive-options", () -> {
      start();
      long max = StreamRelay.maxKeepAliveIdle();
      Set<Integer> idles = new TreeSet<>();
      for (int i = 0; i < 24; i++) {
        try (HostMux host = host(null)) {
          SocketChannel socket = relay.connections.get(relay.connections.size - 1).socket;
          require(socket.getOption(StandardSocketOptions.SO_KEEPALIVE), "SO_KEEPALIVE on");
          int fd = Sockets.fd(socket), idle, interval, count;
          if (fd >= 0) {
            idle = LibClaj.getsockopt(fd, LibClaj.IPPROTO_TCP, LibClaj.TCP_KEEPIDLE);
            interval = LibClaj.getsockopt(fd, LibClaj.IPPROTO_TCP, LibClaj.TCP_KEEPINTVL);
            count = LibClaj.getsockopt(fd, LibClaj.IPPROTO_TCP, LibClaj.TCP_KEEPCNT);
          } else if (Sockets.KEEP_IDLE != null) {
            idle = socket.getOption(Sockets.KEEP_IDLE);
            interval = socket.getOption(Sockets.KEEP_INTERVAL);
            count = socket.getOption(Sockets.KEEP_COUNT);
          } else {
            System.out.println("  keepalive delays: not settable on Java " + System.getProperty("java.version") + ", system defaults");
            return;
          }
          require(idle >= ClajConfig.keepAlive.get() && idle <= max && interval == ClajConfig.keepAlive.get() / 2 &&
                  count == StreamRelay.KEEP_ALIVE_COUNT, "delays " + idle + "/" + interval + "/" + count);
          idles.add(idle);
        }
      }
      require(idles.size() >= 3, "idle times spread: " + idles);
      System.out.println("  keepalive delays set: idle " + idles + " s, " + ClajConfig.keepAlive.get() / 2 + " s interval, " + StreamRelay.KEEP_ALIVE_COUNT + " probes");
    });
    checks.put("keepalive-limits", () -> {
      config();
      // The drawn idle time stays within Linux's 32,767 s, whatever the spread.
      ClajConfig.keepAlive.set(21_844);
      require(StreamRelay.maxKeepAliveIdle() == 32_766, "within the limit: " + StreamRelay.maxKeepAliveIdle());
      for (int value : new int[]{21_845, -1}) {
        boolean refused = false;
        try { ClajConfig.keepAlive.set(value); }
        catch (IllegalArgumentException e) { refused = true; }
        require(refused, "refused: " + value);
      }
      config();
    });
    checks.put("socket-tuning-refused", () -> {
      start();
      // Past validation, a value the kernel refuses, as a system refusing the option would: the connection is
      // still admitted, with one warning.
      force(ClajConfig.keepAlive, 70_000); // Past Linux's 32,767 s
      try (HostMux host = host(null)) {
        require(room(host.roomId) != null, "the host was admitted and made its room");
        if (Sockets.fd(relay.connections.get(0).socket) >= 0) require(!relay.nativeKeepAlive, "libclaj's refusal noticed");
      }
    });
    checks.put("socket-options", () -> {
      config();
      start();
      try (HostMux host = host(null)) {
        int fd = Sockets.fd(relay.connections.get(0).socket);
        if (fd < 0) {
          System.out.println("  TCP_NOTSENT_LOWAT: no libclaj or descriptor here, skipped");
          return;
        }
        require(LibClaj.getsockopt(fd, LibClaj.IPPROTO_TCP, LibClaj.TCP_NOTSENT_LOWAT) == 16384, "TCP_NOTSENT_LOWAT set");
        require(LibClaj.getsockopt(-1, LibClaj.IPPROTO_TCP, LibClaj.TCP_NOTSENT_LOWAT) == -9, "EBADF as -errno");
      }
    });
    checks.put("close-short-write", () -> {
      // Small buffers force a short first write while lingering: what's left must still get OP_WRITE.
      // 32,768 B, not 64 KiB: a larger DATA crosses the relay's read boundary and arms OP_WRITE by chance.
      config();
      ClajConfig.lingerTimeout.set(0.9f);
      start();
      for (String variant : new String[]{"close", "room-close"}) {
        try (HostMux host = host(null); Socket socket = new Socket()) {
          socket.setReceiveBufferSize(1024);
          socket.connect(address, TIMEOUT);
          new StreamClient(socket, false, TIMEOUT).join(host.roomId, (short)-1, TYPE);
          int id = host.read().id;
          host.read();
          room(host.roomId).streams.get(id).socket.socket().setSendBufferSize(1024);
          byte[] sent = new byte[32768];
          new Random(1).nextBytes(sent);
          host.frame(DATA, id, sent, 0, sent.length);
          if (variant.equals("close")) host.close(id).flush();
          else host.control(ROOM_CLOSE, null).flush();
          Thread.sleep(120);
          ByteArrayOutputStream got = new ByteArrayOutputStream();
          InputStream in = socket.getInputStream();
          byte[] buffer = new byte[8192];
          for (int n; (n = in.read(buffer)) >= 0;) got.write(buffer, 0, n);
          require(Arrays.equals(got.toByteArray(), sent), variant + ": " + got.size() + "/32768 B, then EOF");
        }
      }
    });
    checks.put("close-unread-input", () -> {
      start();
      for (String variant : new String[]{"close", "close-and-leave", "room-teardown"}) {
        try (HostMux host = host(null)) {
          Socket socket = new Socket();
          socket.setReceiveBufferSize(1024);
          socket.connect(address, TIMEOUT);
          StreamClient session = new StreamClient(socket, false, TIMEOUT);
          require(session.join(host.roomId, (short)-1, TYPE) == null, "joined");
          int id = host.read().id;
          host.read();
          socket.getOutputStream().write(new byte[100_000]); // No upstream credit: unread at the relay.
          byte[] data = new byte[WINDOW_SIZE];
          new Random(7).nextBytes(data);
          host.frame(DATA, id, data, 0, 65535).frame(DATA, id, data, 65535, 1);
          if (!variant.equals("room-teardown")) host.close(id);
          host.flush();
          // A host leaving shuts its output down: closing with unread input (the relay's WINDOW grants) would send
          // a reset, and a reset makes the relay's kernel drop the host bytes not read yet, the last DATA included.
          if (!variant.equals("close")) host.socket.shutdownOutput();
          long closedAt = System.nanoTime();
          Thread.sleep(100);
          byte[] back = new byte[data.length];
          DataInputStream in = new DataInputStream(socket.getInputStream());
          int got = 0;
          while (got < back.length) {
            int n = in.read(back, got, back.length - got);
            if (n < 0) throw new AssertionError(variant + ": " + got + " of " + back.length + " B, then EOF after " +
                                                (System.nanoTime() - closedAt) / 1_000_000 + " ms");
            got += n;
          }
          require(Arrays.equals(data, back), variant + ": the whole window arrives");
          require(in.read() == -1, variant + ": then a clean EOF, not a reset");
          socket.close();
        }
      }
    });
    checks.put("zero-credit-eof", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket closed = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int closedId = host.read().id;
        host.read();
        Socket reset = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int resetId = host.read().id;
        host.read();
        Socket single = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int singleId = host.read().id;
        host.read();
        // No grant at all: the read-ahead byte shows the EOF and the reset.
        closed.close();
        HostMux.Frame fin = host.read();
        require(fin.type == FIN && fin.id == closedId, "EOF without credit: FIN, got " + fin);
        reset.setSoLinger(true, 0);
        reset.close();
        HostMux.Frame abort = host.read();
        require(abort.type == CLOSE && abort.id == resetId, "reset without credit: CLOSE, got " + abort);
        require(room(host.roomId).streams.get(resetId) == null, "reset stream released");
        host.close(closedId).flush();
        // A read-ahead byte alone goes out with the next grant.
        single.getOutputStream().write(9);
        Thread.sleep(100);
        host.window(singleId, 10).flush();
        HostMux.Frame data = host.read();
        require(data.type == DATA && data.id == singleId && data.payload.length == 1 && data.payload[0] == 9,
                "read-ahead byte delivered on the grant, got " + data);
        single.close();
      }
    });
    checks.put("join-helper-closes", () -> {
      // A fake relay that completes stage 1, then never answers the join.
      try (ServerSocket fake = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        Throwable[] failure = new Throwable[1];
        Thread caller = new Thread(() -> {
          try { StreamClient.join(new InetSocketAddress(InetAddress.getLoopbackAddress(), fake.getLocalPort()), 1, (short)-1, TYPE, 200); }
          catch (Throwable e) { failure[0] = e; }
        });
        caller.start();
        try (Socket accepted = fake.accept()) {
          accepted.getOutputStream().write(frame(HELLO, ByteBuffer.allocate(9).put((byte)VERSION).putLong(42).array()));
          DataInputStream in = new DataInputStream(accepted.getInputStream());
          message(in, HELLO_TCP);
          accepted.getOutputStream().write(frame(WELCOME, new byte[]{0}));
          message(in, JOIN_REQUEST);
          caller.join(3000);
          require(failure[0] instanceof SocketTimeoutException, "the helper times out: " + failure[0]);
          accepted.setSoTimeout(TIMEOUT);
          require(accepted.getInputStream().read() == -1, "and closes its socket");
        }
      }
    });
    checks.put("half-close-client", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int id = host.read().id;
        host.read();
        host.window(id, WINDOW_SIZE).flush();
        socket.getOutputStream().write("request".getBytes("ASCII"));
        socket.shutdownOutput();
        HostMux.Frame data = host.read();
        require(data.type == DATA && new String(data.payload, "ASCII").equals("request"), "data first");
        require(host.read().type == FIN, "then FIN");
        // The other direction still works after the client's half-close.
        host.data(id, "reply".getBytes("ASCII")).fin(id).flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] reply = new byte[5];
        in.readFully(reply);
        require(new String(reply, "ASCII").equals("reply"), "reply after half-close");
        require(in.read() == -1, "EOF after host FIN");
        Thread.sleep(100);
        require(room(host.roomId).clients.size == 0, "stream released after both FINs");
        socket.close();
      }
    });
    checks.put("half-close-host", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int id = host.read().id;
        host.read();
        host.window(id, WINDOW_SIZE).data(id, "bye".getBytes("ASCII")).fin(id).flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] bye = new byte[3];
        in.readFully(bye);
        require(in.read() == -1, "client sees EOF after the host FIN");
        socket.getOutputStream().write("still here".getBytes("ASCII"));
        HostMux.Frame data = host.read();
        require(data.type == DATA && new String(data.payload, "ASCII").equals("still here"), "client can still send");
        socket.shutdownOutput();
        require(host.read().type == FIN, "client FIN");
        socket.close();
      }
    });
    checks.put("host-leaves", () -> {
      start();
      HostMux host = host(null);
      Socket a = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
      int id = host.read().id;
      host.read();
      byte[] last = new byte[30000];
      host.data(id, last).flush();
      host.socket.shutdownOutput(); // An EOF on the mux, like a close.
      byte[] back = new byte[last.length];
      new DataInputStream(a.getInputStream()).readFully(back);
      require(a.getInputStream().read() == -1, "queued bytes delivered, then EOF");
      require(roomCount() == 0, "room closed");
      requireClosed(host.socket, "host closed");
      a.close();
      host.close();
    });
    // end region
    // region credits
    checks.put("credit-one-window-queued", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        socket.setReceiveBufferSize(4096);
        int id = host.read().id;
        host.read();
        // The client never reads: the host only gets credit back as the kernel buffers drain.
        byte[] chunk = new byte[16384];
        for (int i = 0; i < 4; i++) host.data(id, chunk);
        host.flush();
        Thread.sleep(300);
        StreamConnection client = room(host.roomId).streams.get(id);
        require(client.out.size() <= WINDOW_SIZE, "relay queue bounded by the window: " + client.out.size());
        require(client.downCredit + client.out.size() + client.returnCredit <= WINDOW_SIZE, "credit invariant");
        socket.close();
      }
    });
    checks.put("credit-abuse", () -> {
      start();
      String[] cases = {"grant", "zero", "future", "open", "length", "empty"};
      for (String abuse : cases) {
        try (HostMux host = host(null); HostMux other = host(null)) {
          Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
          int id = host.read().id;
          host.read();
          switch (abuse) {
            case "overflow": socket.close(); continue;
            case "grant": host.window(id, WINDOW_SIZE).window(id, 1); break;
            case "zero": host.window(id, 0); break;
            case "future": host.data(id + 5, new byte[1]); break;
            case "open": host.frame(OPEN, id, new byte[0], 0, 0); break;
            case "length": host.frame(WINDOW, id, new byte[3], 0, 3); break;
            case "empty": host.frame(DATA, id, new byte[0], 0, 0); break;
          }
          host.flush();
          requireClosed(host.socket, abuse + ": host closed");
          socket.close();
          require(room(other.roomId) != null, abuse + ": other rooms untouched");
        }
      }
    });
    checks.put("credit-overflow", () -> {
      start();
      try (HostMux host = host(null); HostMux other = host(null)) {
        Socket socket = stalledClient(host);
        int id = room(host.roomId).lastId;
        int credit = fill(host, id);
        host.data(id, new byte[credit + 1]).flush();
        requireKicked(host, id, "DATA past the credit");
        requireClosed(socket, "overrun client");
        // The room goes on: a new client joins it.
        Socket next = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        HostMux.Frame open = host.read();
        require(open.type == OPEN && open.id > id, "the room still takes clients, got " + open);
        require(room(other.roomId) != null, "other rooms untouched");
        next.close();
      }
    });
    checks.put("credit-withheld", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket socket = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int id = host.read().id;
        host.read();
        socket.getOutputStream().write(new byte[100_000]);
        Thread.sleep(200);
        require(host.socket.getInputStream().available() == 0, "no DATA without credit");
        host.window(id, 1000).flush();
        HostMux.Frame data = host.read();
        require(data.type == DATA && data.payload.length == 1000, "exactly the credit: " + data);
        Thread.sleep(200);
        require(host.socket.getInputStream().available() == 0, "then waits again");
        socket.close();
      }
    });
    checks.put("credit-turns-both-ready", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket flood = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int floodId = host.read().id;
        host.read();
        Socket quiet = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int quietId = host.read().id;
        host.read();
        flood.getOutputStream().write(new byte[WINDOW_SIZE]);
        quiet.getOutputStream().write(new byte[10]);
        Thread.sleep(100);
        host.window(floodId, WINDOW_SIZE).window(quietId, WINDOW_SIZE).flush();
        int before = 0;
        HostMux.Frame frame;
        while ((frame = host.read()).id == floodId) {
          require(frame.payload.length <= SLICE_SIZE, "slices of at most 4 KiB");
          before++;
        }
        require(frame.id == quietId && frame.payload.length == 10, "quiet stream served");
        require(before <= 1, "at most one flood slice before it, got " + before);
        flood.close();
        quiet.close();
      }
    });
    // end region
    // region datagrams
    checks.put("datagram-udp-host", () -> {
      start();
      StreamClient hostSession = StreamClient.connect(address, true, TIMEOUT);
      try (HostMux host = hostSession.createRoom(TYPE, null)) {
        StreamClient client = StreamClient.connect(address, true, TIMEOUT);
        require(client.join(host.roomId, (short)-1, TYPE) == null, "joined");
        HostMux.Frame open = host.read();
        require(open.hasUdp(), "OPEN says the client has UDP");
        host.read();
        host.window(open.id, 1).flush(); // Acknowledges the stream.
        client.datagrams.send(new DatagramPacket(new byte[]{1, 2, 3}, 3));
        DatagramPacket packet = receive(hostSession.datagrams);
        ByteBuffer got = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
        require(got.getInt() == open.id && got.remaining() == 3 && got.get() == 1, "client -> UDP host with conId");
        hostSession.datagrams.send(new DatagramPacket(ByteBuffer.allocate(6).putInt(open.id).put((byte)7).put((byte)8).array(), 6));
        packet = receive(client.datagrams);
        require(packet.getLength() == 2 && packet.getData()[0] == 7, "UDP host -> client, raw");
        // TCP and UDP aren't ordered: the broadcast only reaches streams whose acknowledgement the relay has read.
        // A DATA the client receives proves the WINDOW before it was read, even when the kernel delays TCP.
        host.data(open.id, new byte[]{42}).flush();
        require(client.socket.getInputStream().read() == 42, "TCP barrier");
        hostSession.datagrams.send(new DatagramPacket(ByteBuffer.allocate(5).putInt(BROADCAST).put((byte)9).array(), 5));
        require(receive(client.datagrams).getData()[0] == 9, "broadcast datagram");
        client.close();
      }
    });
    checks.put("datagram-tcp-host", () -> {
      start();
      try (HostMux host = host(null)) {
        StreamClient client = StreamClient.connect(address, true, TIMEOUT);
        require(client.join(host.roomId, (short)-1, TYPE) == null, "joined");
        HostMux.Frame open = host.read();
        host.read();
        client.datagrams.send(new DatagramPacket(new byte[]{4, 5}, 2));
        HostMux.Frame dgram = host.read();
        require(dgram.type == DGRAM && dgram.id == open.id && dgram.payload.length == 2, "client -> TCP host as DGRAM");
        host.frame(DGRAM, open.id, new byte[]{6}, 0, 1).flush();
        require(receive(client.datagrams).getData()[0] == 6, "DGRAM -> client datagram");

        Socket tcpOnly = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        HostMux.Frame second = host.read();
        require(!second.hasUdp(), "OPEN says TCP only");
        host.read();
        host.frame(DGRAM, second.id, new byte[]{1}, 0, 1).window(second.id, 1).flush();
        Thread.sleep(100);
        require(roomCount() == 1, "datagram to a TCP-only client dropped quietly");
        tcpOnly.close();
        client.close();
      }
    });
    checks.put("datagram-ipv6", () -> {
      // The UDP host path over IPv6, both ways and broadcast: addresses of the other family than the checks'.
      InetAddress loopback6;
      try {
        loopback6 = InetAddress.getByName("::1");
        new DatagramSocket(0, loopback6).close();
      } catch (IOException e) {
        System.out.println("     datagram-ipv6: no IPv6 loopback, skipped");
        return;
      }
      start(loopback6);
      StreamClient hostSession = StreamClient.connect(address, true, TIMEOUT);
      try (HostMux host = hostSession.createRoom(TYPE, null)) {
        StreamClient client = StreamClient.connect(address, true, TIMEOUT);
        require(client.join(host.roomId, (short)-1, TYPE) == null, "joined over IPv6");
        HostMux.Frame open = host.read();
        host.read();
        host.window(open.id, 1).data(open.id, new byte[]{42}).flush();
        require(client.socket.getInputStream().read() == 42, "TCP barrier");
        client.datagrams.send(new DatagramPacket(new byte[]{1, 2, 3}, 3));
        ByteBuffer got = ByteBuffer.wrap(receive(hostSession.datagrams).getData());
        require(got.getInt() == open.id && got.get() == 1, "client -> UDP host over IPv6");
        hostSession.datagrams.send(new DatagramPacket(ByteBuffer.allocate(5).putInt(BROADCAST).put((byte)9).array(), 5));
        require(receive(client.datagrams).getData()[0] == 9, "broadcast over IPv6");
        client.close();
      }
    });
    checks.put("peer-map-sequential-ports", () -> {
      // One address with 4,096 sequential ports, as behind a NAT: a lookup of an unknown port, which any
      // datagram can trigger, must stay short. The old hash made it scan 4,097 slots.
      PeerMap map = new PeerMap();
      long lo = PeerMap.MAPPED | 0x0a000001L;
      for (int port = 16_384; port < 20_480; port++) {
        StreamConnection con = new StreamConnection(null, null, new InetSocketAddress(0), 0, 0);
        con.udpLo = lo;
        con.udpPort = port;
        map.put(con);
      }
      int mask = map.values.length - 1, longest = 0;
      for (int port = 1; port < 65_536; port++) {
        int run = 0;
        for (int i = map.slot(0, lo, port, mask); map.values[i] != null; i = i + 1 & mask) run++;
        longest = Math.max(longest, run);
        StreamConnection found = map.get(0, lo, port);
        require(port >= 16_384 && port < 20_480 ? found != null && found.udpPort == port : found == null,
                "lookup of port " + port);
      }
      require(longest < 100, "longest probe run " + longest + " slots");
    });
    checks.put("datagram-batch", () -> {
      // Bursts longer than a receive batch, with the host's sends pointing into the receive slots: every
      // datagram arrives once, whole and in order (loopback drops none at these sizes), and an oversized one is
      // dropped.
      start();
      StreamClient hostSession = StreamClient.connect(address, true, TIMEOUT);
      try (HostMux host = hostSession.createRoom(TYPE, null)) {
        int clients = 3, burst = 40;
        StreamClient[] c = new StreamClient[clients];
        int[] ids = new int[clients];
        for (int k = 0; k < clients; k++) {
          c[k] = StreamClient.connect(address, true, TIMEOUT);
          require(c[k].join(host.roomId, (short)-1, TYPE) == null, "joined");
          HostMux.Frame open;
          while ((open = host.read()).type != OPEN) {} // The WINDOW returned for the previous barrier byte.
          ids[k] = open.id;
          host.window(ids[k], 1).data(ids[k], new byte[]{42}).flush();
          require(c[k].socket.getInputStream().read() == 42, "TCP barrier " + k);
        }
        for (int n = 0; n < burst; n++)
          for (int k = 0; k < clients; k++)
            c[k].datagrams.send(new DatagramPacket(ByteBuffer.allocate(8).putInt(k).putInt(n).array(), 8));
        int[] next = new int[clients];
        for (int i = 0; i < burst * clients; i++) {
          DatagramPacket packet = receive(hostSession.datagrams);
          ByteBuffer got = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
          require(packet.getLength() == 12, "prefix and payload, got " + packet.getLength() + " B");
          int id = got.getInt(), k = got.getInt(), n = got.getInt();
          require(k >= 0 && k < clients && id == ids[k] && n == next[k]++, "datagram " + i + " in order");
        }
        c[0].datagrams.send(new DatagramPacket(new byte[MAX_DATAGRAM_SIZE + 1], MAX_DATAGRAM_SIZE + 1));
        c[0].datagrams.send(new DatagramPacket(new byte[]{5}, 1));
        require(receive(hostSession.datagrams).getLength() == 5, "oversized datagram dropped, the next one sent");
        for (int n = 0; n < burst; n++)
          hostSession.datagrams.send(new DatagramPacket(ByteBuffer.allocate(8).putInt(BROADCAST).putInt(n).array(), 8));
        for (int k = 0; k < clients; k++)
          for (int n = 0; n < burst; n++) {
            DatagramPacket packet = receive(c[k].datagrams);
            require(packet.getLength() == 4 && ByteBuffer.wrap(packet.getData()).getInt() == n, "broadcast " + n + " to " + k);
          }
        for (StreamClient client : c) client.close();
      }
    });
    checks.put("broadcast-data", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket a = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int idA = host.read().id;
        host.read();
        Socket b = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        int idB = host.read().id;
        host.read();
        host.window(idA, 1).window(idB, 1).flush(); // Both acknowledged.
        Socket late = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        host.read();
        host.read();
        host.frame(DATA, BROADCAST, "all".getBytes("ASCII"), 0, 3).flush();
        for (Socket socket : new Socket[]{a, b}) {
          byte[] got = new byte[3];
          new DataInputStream(socket.getInputStream()).readFully(got);
          require(new String(got, "ASCII").equals("all"), "broadcast delivered");
        }
        late.setSoTimeout(300);
        try {
          late.getInputStream().read();
          throw new AssertionError("unacknowledged stream got the broadcast");
        } catch (SocketTimeoutException expected) {}
        Socket stalled = stalledClient(host);
        int id = room(host.roomId).lastId;
        int credit = fill(host, id);
        host.frame(DATA, BROADCAST, new byte[credit + 1], 0, credit + 1).flush();
        requireKicked(host, id, "broadcast past the stalled client's credit");
        requireClosed(stalled, "stalled client");
        for (Socket socket : new Socket[]{a, b}) {
          new DataInputStream(socket.getInputStream()).readFully(new byte[credit + 1]);
        }
        a.close(); b.close(); late.close();
      }
    });
    // end region
    // region limits
    checks.put("budget-admission", () -> {
      start();
      long room = relay.hostReserve, stream = relay.streamReserve, pending = StreamRelay.PENDING_RESERVE;
      stop();
      // Room for: two pending connections, one host and one stream.
      ClajConfig.memoryBudget.set(3 * pending + room + stream);
      start();
      try (HostMux host = host(null); StreamClient a = StreamClient.connect(address, false, TIMEOUT);
           StreamClient b = StreamClient.connect(address, false, TIMEOUT)) {
        require(a.join(host.roomId, (short)-1, TYPE) == null, "first stream fits");
        require(b.join(host.roomId, (short)-1, TYPE) == RejectReason.serverFull, "second refused: serverFull");
        try (Socket refused = raw()) {
          requireClosed(refused, "no budget left even for a pending connection");
        }
        int id = host.read().id;
        host.read();
        a.close();
        // Without credit the relay doesn't read the client, the EOF shows with the next grant.
        host.window(id, 1).flush();
        require(host.read().type == FIN, "client EOF");
        host.close(id).flush();
        Thread.sleep(200);
        require(b.join(host.roomId, (short)-1, TYPE) == null, "budget released on close");
      }
    });
    checks.put("connect-filter", () -> {
      // The server's filter, the one the 2.x port uses.
      start();
      List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
      arc.net.Server.ServerConnectFilter blacklist = server.getConnectFilter();
      server.setConnectFilter(address -> {
        seen.add(address);
        return false;
      });
      try (Socket refused = raw()) {
        requireClosed(refused, "refused by the connect filter");
      } finally {
        server.setConnectFilter(blacklist);
      }
      require(seen.equals(java.util.Collections.singletonList(address.getAddress().getHostAddress())), "filter saw " + seen);
      try (HostMux host = host(null)) {
        require(host.roomId != 0, "admitted without the filter");
      }
    });
    checks.put("connection-caps", () -> {
      config();
      ClajConfig.connectionLimit.set(3);
      start();
      List<Socket> sockets = new ArrayList<>();
      try {
        for (int i = 0; i < 3; i++) {
          sockets.add(raw());
          token(sockets.get(i));
        }
        try (Socket extra = raw()) {
          requireClosed(extra, "connections per address capped");
        }
        try (HostMux host = host(null)) {
          throw new AssertionError("host admitted past the cap " + host.roomId);
        } catch (IOException expected) {}
        sockets.get(0).close();
        Thread.sleep(100);
        try (HostMux host = host(null)) {
          require(host.roomId != 0, "a closed connection no longer counts");
        }
      } finally {
        for (Socket socket : sockets) socket.close();
      }
      stop();
      config();
      ClajConfig.maxConnections.set(2);
      start();
      try (Socket a = raw(); Socket b = raw(); Socket c = raw()) {
        token(a);
        token(b);
        requireClosed(c, "max connections");
      }
    });
    checks.put("lazy-buffers", () -> {
      start();
      List<Socket> sockets = new ArrayList<>();
      try (HostMux host = host(null)) {
        for (int i = 0; i < 5; i++) {
          sockets.add(raw());
          token(sockets.get(i));
        }
        sockets.add(StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT));
        Thread.sleep(200);
        // Idle pending connections hold no buffer, an idle stream at most its first small one.
        for (StreamConnection con : relay.connections) {
          if (con.isHost()) continue;
          byte[] out = (byte[])arrayOf(con.out);
          require(con.request == null && (out == null || !con.isPending() && out.length <= ByteRing.INITIAL_CAPACITY),
                  "idle " + con + " holds " + (out == null ? 0 : out.length) + " bytes");
        }
      } finally {
        for (Socket socket : sockets) socket.close();
      }
    });
    checks.put("error-isolation-tcp-host", () -> {
      start();
      try (HostMux broken = host(null); HostMux healthy = host(null)) {
        Socket a = StreamClient.join(address, broken.roomId, (short)-1, TYPE, TIMEOUT);
        int idA = broken.read().id;
        broken.read();
        Socket b = StreamClient.join(address, healthy.roomId, (short)-1, TYPE, TIMEOUT);
        int idB = healthy.read().id;
        healthy.read();
        // Corrupts one stream, as a bug would: its next DATA overflows a ring.
        room(broken.roomId).streams.get(idA).out.limit = 0;
        broken.data(idA, new byte[10]).flush();
        requireClosed(broken.socket, "unexpected error closes that room");
        healthy.data(idB, new byte[]{42}).flush();
        require(b.getInputStream().read() == 42, "the loop and other rooms go on");
        a.close();
        b.close();
      }
    });
    checks.put("error-isolation-tcp-client", () -> {
      start();
      // Corrupts a host queue, as a bug would: at 128 B the client's next turn overflows it, at 0 so does the CLOSE
      // telling the host. Only that client goes, or only that room.
      for (int limit : new int[]{128, 0}) {
        try (HostMux broken = host(null); HostMux healthy = host(null)) {
          Socket a = StreamClient.join(address, broken.roomId, (short)-1, TYPE, TIMEOUT);
          int idA = broken.read().id;
          broken.read();
          Socket b = StreamClient.join(address, healthy.roomId, (short)-1, TYPE, TIMEOUT);
          int idB = healthy.read().id;
          healthy.read();
          broken.window(idA, WINDOW_SIZE).flush();
          StreamRoom room = room(broken.roomId);
          long end = System.nanoTime() + TIMEOUT * 1_000_000L;
          while (room.streams.get(idA).upCredit == 0 && System.nanoTime() < end) Thread.sleep(1);
          room.mux.out.limit = limit;
          a.getOutputStream().write(42);
          requireClosed(a, "unexpected error in a turn closes that client");
          if (limit > 0) {
            broken.socket.setSoTimeout(TIMEOUT);
            HostMux.Frame frame;
            do frame = broken.read();
            while (frame.type == WINDOW);
            require(frame.type == CLOSE && frame.id == idA, "a valid CLOSE, no half DATA frame: " + frame);
          } else requireClosed(broken.socket, "a host that can't be told closes its room");
          require(app.getMainThread().isAlive() && app.getWaiter() == relay, "the loop goes on");
          healthy.data(idB, new byte[]{42}).flush();
          require(b.getInputStream().read() == 42, "the other rooms go on");
          b.close();
        }
      }
    });
    checks.put("error-isolation-udp-hello", () -> {
      start();
      try (HostMux healthy = host(null); Socket pending = raw();
           DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
        Socket client = StreamClient.join(address, healthy.roomId, (short)-1, TYPE, TIMEOUT);
        int id = healthy.read().id;
        healthy.read();
        long token = token(pending);
        // Corrupts the pending connection, as a bug would: its WELCOME overflows its ring.
        relay.tokens.get(token).out.limit = 0;
        udp.send(helloUdp(token));
        requireClosed(pending, "the failing peer is closed");
        require(app.getMainThread().isAlive() && app.getWaiter() == relay, "the loop goes on");
        healthy.data(id, new byte[]{42}).flush();
        require(client.getInputStream().read() == 42, "the healthy room goes on");
        client.close();
      }
    });
    // end region
    // region server
    checks.put("console-rooms", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket client = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        host.read(); host.read();
        String sid = room(host.roomId).sid;
        List<String> lines = console("rooms", "rooms info", "status");
        require(lines.stream().anyMatch(l -> l.contains(sid) && l.contains("2 clients") && l.contains("Stream")),
                "listed by 'rooms': " + lines);
        require(lines.stream().anyMatch(l -> l.contains("Room " + sid) && l.contains("stream")), "'rooms info'");
        require(lines.stream().filter(l -> l.contains("[C] Connection")).count() == 1, "its client listed");
        require(lines.stream().anyMatch(l -> l.contains("Load: 1 rooms, 2 clients, 2 connections")), "'status' counts: " + lines);
        // Closed from the console, with a reason: the host is told, the client gets its EOF.
        console("close room " + sid + " afk");
        HostMux.Frame closed = host.read();
        require(closed.type == CONTROL && closed.payload[1] == CloseReason.afk.ordinal(), "host told: " + closed);
        requireClosed(client, "client closed");
        require(roomCount() == 0, "room closed");
      }
    });
    checks.put("routines", () -> {
      start();
      // The address blacklist refuses at accept, through the server's connect filter.
      ClajConfig.blacklist.add(address.getAddress().getHostAddress());
      try (Socket refused = raw()) {
        requireClosed(refused, "blacklisted address refused");
      } finally {
        ClajConfig.blacklist.clear();
      }
      // The type blacklist refuses the room, the connection stays in stage 2.
      ClajConfig.typeBlacklist.add(TYPE);
      try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        client.createRoom(TYPE, null);
        throw new AssertionError("blacklisted type accepted");
      } catch (StreamClient.Refused e) {
        require(e.reason == CloseReason.blacklisted, "type blacklist: " + e.reason);
      } finally {
        ClajConfig.typeBlacklist.clear();
      }
      // The join rate limit per address acts as a room not found: joinLimit * 2 joins per minute.
      ClajConfig.joinLimit.set(1);
      try (HostMux host = host(null); StreamClient a = StreamClient.connect(address, false, TIMEOUT)) {
        require(a.join(host.roomId, (short)4, TYPE) == null, "first join");
        try (StreamClient b = StreamClient.connect(address, false, TIMEOUT)) {
          require(b.join(host.roomId, (short)4, TYPE) == null, "second join");
        }
        try (StreamClient c = StreamClient.connect(address, false, TIMEOUT)) {
          require(c.join(host.roomId, (short)4, TYPE) == RejectReason.roomNotFound, "rate limited as not found");
        }
      }
      // The data rate kicks a stage 2 connection sending too many requests: each costs 1 KB at least.
      ClajConfig.clientRate.set(0.002f); // 2 KB/s, a burst of 6 KB: 6 requests
      try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        for (int i = 0; i < 5; i++) require(client.serverVersion() == 4, "request " + i);
        send(client.socket, VERSION_REQUEST, new byte[0]);
        requireClosed(client.socket, "kicked for going over its data rate");
      }
    });
    checks.put("plugin-events", () -> {
      start();
      List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
      arc.func.Cons<ClajEvents.ClientConnectedEvent> connected = e -> seen.add("connected " + e.connection.isStream());
      arc.func.Cons<ClajEvents.RoomCreatedEvent> created = e -> seen.add("created " + e.room.isStream());
      arc.func.Cons<ClajEvents.ConnectionJoinedEvent> joined = e -> seen.add("joined " + e.connection.isStream());
      arc.func.Cons<ClajEvents.ConnectionJoinRejectedEvent> rejected = e -> seen.add("rejected " + e.reason);
      arc.func.Cons<ClajEvents.ConnectionLeftEvent> left = e -> seen.add("left " + e.room.isHost(e.connection));
      arc.func.Cons<ClajEvents.RoomClosedEvent> closed = e -> seen.add("closed " + e.room.isStream());
      arc.func.Cons<ClajEvents.ClientDisonnectedEvent> gone = e -> seen.add("disconnected " + e.reason);
      Events.on(ClajEvents.ClientConnectedEvent.class, connected);
      Events.on(ClajEvents.RoomCreatedEvent.class, created);
      Events.on(ClajEvents.ConnectionJoinedEvent.class, joined);
      Events.on(ClajEvents.ConnectionJoinRejectedEvent.class, rejected);
      Events.on(ClajEvents.ConnectionLeftEvent.class, left);
      Events.on(ClajEvents.RoomClosedEvent.class, closed);
      Events.on(ClajEvents.ClientDisonnectedEvent.class, gone);
      try {
        HostMux host = host(null);
        try (StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
          require(client.join(42, (short)-1, TYPE) == RejectReason.roomNotFound, "denied");
          require(client.join(host.roomId, (short)-1, TYPE) == null, "joined");
          host.read(); host.read();
        }
        require(host.read().type == FIN, "client EOF");
        host.close();
        long end = System.nanoTime() + TIMEOUT * 1_000_000L;
        while (seen.size() < 10 && System.nanoTime() < end) Thread.sleep(10);
        // ClajRelay.onDisconnect's order: the disconnection, then the room it leaves.
        require(seen.equals(Arrays.asList("connected true", "created true", "connected true", "rejected roomNotFound",
                                          "joined true", "disconnected closed", "left true", "closed true",
                                          "left false", "disconnected closed")), "events: " + seen);
      } finally {
        Events.remove(ClajEvents.ClientConnectedEvent.class, connected);
        Events.remove(ClajEvents.RoomCreatedEvent.class, created);
        Events.remove(ClajEvents.ConnectionJoinedEvent.class, joined);
        Events.remove(ClajEvents.ConnectionJoinRejectedEvent.class, rejected);
        Events.remove(ClajEvents.ConnectionLeftEvent.class, left);
        Events.remove(ClajEvents.RoomClosedEvent.class, closed);
        Events.remove(ClajEvents.ClientDisonnectedEvent.class, gone);
      }
    });
    checks.put("connection-ids", () -> {
      start();
      try (ArcClient victim = arcClient(); ArcClient arcHost = arcClient()) {
        int arcId = victim.getID();
        ClajConnection[] arc = {null};
        // The stream relay's next id would be the Arc one: it takes the next free one instead.
        onMain(() -> {
          arc[0] = server.connections.get(arcId);
          relay.lastUid = arcId + 1;
        });
        try (StreamClient stream = StreamClient.connect(address, false, TIMEOUT)) {
          int[] streamId = {0};
          onMain(() -> {
            streamId[0] = relay.connections.get(relay.connections.size - 1).uid;
            require(server.connections.get(arcId) == arc[0] && server.connections.get(streamId[0]).isStream(),
                    "both in the registry");
          });
          require(streamId[0] != arcId, "the stream id skips the Arc one");
          // An Arc host closing its Arc client touches nothing else, by id or by room.
          RoomCreationRequestPacket create = new RoomCreationRequestPacket();
          create.type = TYPE;
          create.version = 4;
          arcHost.sendTCP(create);
          RoomJoinPacket join = new RoomJoinPacket();
          join.roomId = arcHost.read(RoomLinkPacket.class, TIMEOUT).roomId;
          join.type = TYPE;
          victim.sendTCP(join);
          require(arcHost.read(ConnectionJoinPacket.class, TIMEOUT).conID == arcId, "Arc client joined");
          try (HostMux unrelated = stream.createRoom(TYPE, null)) {
            ConnectionClosedPacket kick = new ConnectionClosedPacket();
            kick.conID = arcId;
            kick.reason = DcReason.closed;
            arcHost.sendTCP(kick);
            long end = System.nanoTime() + TIMEOUT * 1_000_000L;
            boolean[] left = {false};
            while (!left[0] && System.nanoTime() < end) {
              onMain(() -> left[0] = arc[0].currentRoom() == null);
              Thread.sleep(5);
            }
            require(left[0], "the Arc client left its room");
            unrelated.ping().flush();
            require(room(unrelated.roomId) != null, "the stream room is untouched");
          }
        }
      }
      // An Arc connection drawing an id already taken is refused, the registered one stays.
      try (HostMux host = host(null)) {
        ClajConnection[] taken = {null}, duplicate = {null};
        boolean[] closed = {false};
        onMain(() -> {
          taken[0] = relay.room(host.roomId).host;
          arc.net.Connection connection = new arc.net.Connection() {
            @Override public int getID() { return taken[0].id; }
            @Override public boolean isConnected() { return !closed[0]; }
            @Override public void close(DcReason reason) { closed[0] = true; }
          };
          try {
            duplicate[0] = new ClajConnection(connection, InetAddress.getByName("127.0.0.9"), "127.0.0.9", "duplicate");
          } catch (UnknownHostException e) {
            throw new RuntimeException(e);
          }
          connection.setArbitraryData(duplicate[0]);
          server.onConnect(duplicate[0]);
          require(server.connections.get(taken[0].id) == taken[0], "the registered connection stays");
          require(closed[0] && ClajRelay.toClajCon(connection) == null, "the duplicate is closed and ignored");
          require(!relay.room(host.roomId).containsClient(connection), "rooms tell connections apart, not only ids");
        });
      }
    });
    checks.put("task-fairness", () -> {
      start();
      try (HostMux host = host(null); Socket client = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT)) {
        int id = host.read().id;
        host.read();
        // Short tasks posting themselves, as a busy 2.x side would: the stream still gets its turns.
        java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        Runnable repost = new Runnable() {
          @Override
          public void run() {
            started.countDown();
            if (busy.get()) app.post(this);
          }
        };
        app.post(repost);
        try {
          require(started.await(TIMEOUT, TimeUnit.MILLISECONDS), "task chain started");
          host.data(id, new byte[]{42}).flush();
          client.setSoTimeout(1000);
          require(client.getInputStream().read() == 42, "relayed while tasks keep coming");
          client.getOutputStream().write(7);
          host.window(id, 1).flush();
          require(host.readSkippingWindows().payload[0] == 7, "both ways");
        } catch (SocketTimeoutException e) {
          throw new AssertionError("no stream progress while the main thread has tasks");
        } finally {
          busy.set(false);
        }
      }
    });
    checks.put("server-stop", () -> {
      ClajConfig.controlTimeout.set(10f);
      start();
      try (StreamClient pending = StreamClient.connect(address, false, TIMEOUT)) {
        onMain(() -> server.stop(false));
        requireClosed(pending.socket, "a stage 2 connection is closed with the server");
        long end = System.nanoTime() + TIMEOUT * 1_000_000L;
        int[] left = {-1};
        while (left[0] != 0 && System.nanoTime() < end) {
          onMain(() -> left[0] = server.connections.size + relay.connections.size);
          Thread.sleep(5);
        }
        require(left[0] == 0, "no connection left, in either registry");
        try (Socket refused = raw()) {
          requireClosed(refused, "refused while stopped");
        }
      } finally {
        // Hosted again, on the loopback: the next checks need the server.
        ClajConfig.bindAddress.set("127.0.0.1");
        onMain(() -> server.host(0));
        long end = System.nanoTime() + TIMEOUT * 1_000_000L;
        while (!server.isHosted() && System.nanoTime() < end) Thread.sleep(1);
        ClajConfig.bindAddress.set("");
      }
      require(server.isHosted(), "hosted again");
      try (HostMux host = host(null); StreamClient client = StreamClient.connect(address, false, TIMEOUT)) {
        require(client.join(host.roomId, (short)-1, TYPE) == null, "joined after the restart");
        int id = host.read().id;
        host.read();
        host.close(id).flush();
        requireClosed(client.socket, "the host's CLOSE still reaches it");
      }
    });
    checks.put("bulk-clear", () -> {
      start();
      try (HostMux host = host(null)) {
        Socket a = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        Socket b = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        Set<Integer> ids = new TreeSet<>();
        for (int i = 0; i < 4; i++) ids.add(host.read().id);
        // Not quiet, as the console does: one CLOSE per stream, the wire has no broadcast one.
        onMain(() -> server.clearRoom(relay.room(host.roomId), DcReason.error, false));
        requireClosed(a, "first client closed");
        requireClosed(b, "second client closed");
        Set<Integer> closed = new TreeSet<>();
        for (int i = 0; i < 2; i++) {
          HostMux.Frame frame = host.readSkippingWindows();
          require(frame.type == CLOSE && frame.reason() == DcReason.error, "CLOSE error: " + frame);
          closed.add(frame.id);
        }
        require(closed.equals(ids), "one CLOSE per stream: " + closed);
        // Quiet: the host asked for it, nothing comes back.
        Socket c = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        host.read();
        host.read();
        onMain(() -> server.clearRoom(relay.room(host.roomId), DcReason.closed, true));
        requireClosed(c, "third client closed");
        host.socket.setSoTimeout(300);
        try {
          throw new AssertionError("quiet clear notified: " + host.readSkippingWindows());
        } catch (SocketTimeoutException expected) {}
        require(room(host.roomId) != null, "the room goes on");
        a.close();
        b.close();
        c.close();
      }
    });
    checks.put("plugin-errors", () -> {
      start();
      StreamConnection[] expiring = {null};
      long[] failingRoom = {0};
      arc.func.Cons<ClajEvents.ClientDisonnectedEvent> disconnected = e -> {
        if (expiring[0] != null && e.connection == expiring[0].claj) throw new IllegalStateException("plugin error");
      };
      arc.func.Cons<ClajEvents.RoomClosedEvent> closed = e -> {
        if (e.room.id == failingRoom[0]) throw new IllegalStateException("plugin error");
      };
      Events.on(ClajEvents.ClientDisonnectedEvent.class, disconnected);
      Events.on(ClajEvents.RoomClosedEvent.class, closed);
      try (HostMux healthy = host(null); Socket pending = raw()) {
        Socket client = StreamClient.join(address, healthy.roomId, (short)-1, TYPE, TIMEOUT);
        int id = healthy.read().id;
        healthy.read();
        // A handler throwing for a connection the sweep expires.
        long token = token(pending);
        onMain(() -> expiring[0] = relay.tokens.get(token));
        requireClosed(pending, "the expired connection is closed");
        Thread.sleep(150);
        require(relay.running && app.getWaiter() == relay, "the loop goes on");
        onMain(() -> require(!server.connections.containsKey(expiring[0].uid), "and leaves the registry"));
        healthy.data(id, new byte[]{42}).flush();
        require(client.getInputStream().read() == 42, "the other rooms go on");
        // A handler throwing while a room closes, as its host leaves: the room is still cleaned up.
        HostMux failing = host(null);
        Socket member = StreamClient.join(address, failing.roomId, (short)-1, TYPE, TIMEOUT);
        failing.read();
        failing.read();
        failingRoom[0] = failing.roomId;
        failing.close();
        requireClosed(member, "its client is closed anyway");
        long end = System.nanoTime() + TIMEOUT * 1_000_000L;
        while (room(failing.roomId) != null && System.nanoTime() < end) Thread.sleep(5);
        require(room(failing.roomId) == null, "its room is gone");
        healthy.data(id, new byte[]{43}).flush();
        require(client.getInputStream().read() == 43, "the loop still goes on");
        client.close();
        member.close();
      } finally {
        Events.remove(ClajEvents.ClientDisonnectedEvent.class, disconnected);
        Events.remove(ClajEvents.RoomClosedEvent.class, closed);
      }
    });
    checks.put("data-rate", () -> {
      // Past its rate and burst, a client then a host is read slower, never kicked: TCP pushes back on it.
      for (boolean ofHost : new boolean[]{false, true}) {
        config();
        // 0.1 MB/s, burst 300 KB. A host gets it per client and itself: 0.2 MB/s, burst 600 KB.
        (ofHost ? ClajConfig.hostRate : ClajConfig.clientRate).set(0.1f);
        start();
        int size = ofHost ? 900_000 : 600_000, burst = ofHost ? 600_000 : 300_000, rate = ofHost ? 200_000 : 100_000;
        try (HostMux host = host(null)) {
          Thread echo = echoHost(host);
          Socket client = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
          client.setSoTimeout(10_000);
          long start = System.nanoTime();
          Thread writer = new Thread(() -> {
            try { client.getOutputStream().write(new byte[size]); }
            catch (IOException ignored) {}
          }, "writer");
          writer.setDaemon(true);
          writer.start();
          DataInputStream in = new DataInputStream(client.getInputStream());
          in.readFully(new byte[size]);
          long elapsed = (System.nanoTime() - start) / 1_000_000, least = (size - burst) * 1000L / rate;
          require(elapsed >= least * 8 / 10, (ofHost ? "host" : "client") + " slowed: " + elapsed + " ms for " +
                  size + " B, at least " + least + " ms");
          require(room(host.roomId) != null, "and not kicked");
          client.close();
          echo.interrupt();
        }
        stop();
      }
    });
    checks.put("cleanup-errors", () -> {
      // A handler throwing, RuntimeException or Error, never stops a cleanup halfway: the error comes after.
      start();
      boolean[] failLeft = {false}, failClosed = {false};
      arc.func.Cons<ClajEvents.ConnectionLeftEvent> left = e -> {
        if (failLeft[0]) throw new AssertionError("plugin error");
      };
      arc.func.Cons<ClajEvents.RoomClosedEvent> closed = e -> {
        if (failClosed[0]) throw new IllegalStateException("plugin error");
      };
      Events.on(ClajEvents.ConnectionLeftEvent.class, left);
      Events.on(ClajEvents.RoomClosedEvent.class, closed);
      Throwable[] thrown = {null};
      Runnable expectThrown = () -> {
        require(thrown[0] != null, "the handler's error is thrown after the cleanup");
        thrown[0] = null;
      };
      try (HostMux host = host(null)) {
        // One client removed quietly, and two in bulk.
        Socket quiet = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        host.read();
        host.read();
        failLeft[0] = true;
        onMain(() -> {
          try { server.removeClient(relay.room(host.roomId).clients.first(), DcReason.closed, true); }
          catch (Throwable e) { thrown[0] = e; }
        });
        expectThrown.run();
        requireClosed(quiet, "a client removed quietly is closed");
        failLeft[0] = false;
        Socket a = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        Socket b = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
        for (int i = 0; i < 4; i++) host.read();
        failLeft[0] = true;
        ClajConfig.afkTime.set(1); // Minutes: never reached here
        onMain(() -> {
          try { server.clearRoom(relay.room(host.roomId), DcReason.closed, false); }
          catch (Throwable e) { thrown[0] = e; }
        });
        expectThrown.run();
        requireClosed(a, "first client of a bulk clear closed");
        requireClosed(b, "second client of a bulk clear closed");
        failLeft[0] = false;
        onMain(() -> {
          require(relay.room(host.roomId).clients() == 0, "no client left in the room");
          require(server.routines.afk.containsKey(host.roomId), "the empty room can expire");
          server.routines.cancelRoomAfk(relay.room(host.roomId));
        });
        ClajConfig.afkTime.set(0);
      }
      try (HostMux x = host(null); HostMux y = host(null); HostMux z = host(null)) {
        // Rooms closed by type, as `close list` does.
        failClosed[0] = true;
        onMain(() -> {
          try { server.closeRoomList(TYPE, CloseReason.closed); }
          catch (Throwable e) { thrown[0] = e; }
        });
        expectThrown.run();
        for (HostMux host : new HostMux[]{x, y, z}) requireClosed(host.socket, "each room of the type is closed");
        require(roomCount() == 0, "and none is left");
      } finally { failClosed[0] = false; }
      try (HostMux host = host(null); StreamClient pending = StreamClient.connect(address, false, TIMEOUT)) {
        // The server stopping: its rooms, pending connections and the stopped callback.
        failClosed[0] = true;
        boolean[] stopped = {false};
        onMain(() -> {
          try { server.stop(false, () -> { stopped[0] = true; throw new AssertionError("callback error"); }); }
          catch (Throwable e) { thrown[0] = e; }
        });
        require(thrown[0] instanceof IllegalStateException && thrown[0].getSuppressed().length == 1,
                "the room's error comes first, the callback's is suppressed: " + thrown[0]);
        expectThrown.run();
        require(stopped[0], "the stopped callback runs");
        requireClosed(host.socket, "the room is closed");
        requireClosed(pending.socket, "a pending connection is closed");
        require(!server.isHosted(), "the server is stopped");
      } finally {
        failClosed[0] = false;
        ClajConfig.bindAddress.set("127.0.0.1");
        onMain(() -> server.host(0));
        long end = System.nanoTime() + TIMEOUT * 1_000_000L;
        while (!server.isHosted() && System.nanoTime() < end) Thread.sleep(1);
        ClajConfig.bindAddress.set("");
        Events.remove(ClajEvents.ConnectionLeftEvent.class, left);
        Events.remove(ClajEvents.RoomClosedEvent.class, closed);
      }
      require(server.isHosted(), "hosted again");
    });
    checks.put("protected-api", () -> {
      // Subclasses of ClajRelay override these as protected since 2.x: they stay so.
      Class<?> relay = ClajRelay.class;
      for (java.lang.reflect.Method method : new java.lang.reflect.Method[]{
          relay.getDeclaredMethod("info", String.class, Object[].class),
          relay.getDeclaredMethod("warn", String.class, Object[].class),
          relay.getDeclaredMethod("count", InetAddress.class, int.class),
          relay.getDeclaredMethod("uncount", ClajConnection.class),
          relay.getDeclaredMethod("rejectRoomJoin", ClajConnection.class, ClajRoom.class, long.class, RejectReason.class)})
        require(java.lang.reflect.Modifier.isProtected(method.getModifiers()), method + " is protected");
    });
    checks.put("config-validation", () -> {
      start();
      String batching = ClajConfig.udpBatching.get();
      List<String> lines = console("config keep-alive set -100", "config slice-size set 70000",
                                   "config udp-batching set fast", "config control-timeout set 0",
                                   "config client-rate set -1");
      require(lines.stream().filter(l -> l.contains("Invalid value for setting")).count() == 5, "refused out loud: " + lines);
      require(ClajConfig.keepAlive.get() == 10 && ClajConfig.sliceSize.get() == 4096 &&
              ClajConfig.udpBatching.get().equals(batching) && ClajConfig.controlTimeout.get() == 1.5f &&
              ClajConfig.clientRate.get() == 0f, "the previous values are kept");
      try (Socket socket = raw()) {
        require(token(socket) != 0, "a new connection still gets its HELLO");
      }
      // A file with an invalid value: the reload keeps the previous valid one.
      ClajConfig.keepAlive.set(20);
      ClajConfig.save();
      File file = new File(ClajConfig.fileName);
      String json = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
      String edited = json.replaceAll("(\"keep-alive\"\\s*:\\s*)20", "$1-100");
      require(!edited.equals(json), "config file edited");
      java.nio.file.Files.write(file.toPath(), edited.getBytes("UTF-8"));
      try {
        console("config reload");
        require(ClajConfig.keepAlive.get() == 20, "reload kept " + ClajConfig.keepAlive.get());
      } finally {
        config();
        ClajConfig.save();
      }
    });
    checks.put("oversized-state", () -> {
      start();
      RoomConfigPacket config = new RoomConfigPacket();
      config.isPublic = true;
      try (HostMux host = host(config)) {
        for (int size : new int[]{1024, 1025}) {
          byte[] payload = ByteBuffer.allocate(1 + 2 + size).put((byte)ROOM_STATE).putShort((short)size).array();
          host.frame(CONTROL, ROOM, payload, 0, payload.length).ping().flush();
          if (size > RoomStatePacket.MAX_BUFF_SIZE) break;
          long end = System.nanoTime() + TIMEOUT * 1_000_000L;
          int[] stored = {-1};
          while (stored[0] < 0 && System.nanoTime() < end) {
            onMain(() -> {
              StreamRoom room = relay.room(host.roomId);
              stored[0] = room == null || room.rawState == null ? -1 : room.rawState.remaining();
            });
            Thread.sleep(5);
          }
          require(stored[0] == size, "a state of " + size + " B is kept, got " + stored[0]);
        }
        requireClosed(host.socket, "a larger one is a violation, which closes the room");
      }
    });
    // end region
    // region process
    checks.put("fd-exhaustion", RelayChecks::fdExhaustion);
    checks.put("shutdown", () -> {
      start();
      HostMux host = host(null);
      Socket client = StreamClient.join(address, host.roomId, (short)-1, TYPE, TIMEOUT);
      host.read(); host.read();
      relay.close();
      HostMux.Frame closed = host.read();
      require(closed.type == CONTROL && closed.payload[1] == CloseReason.serverClosed.ordinal(), "host told");
      requireClosed(client, "client closed");
      requireClosed(host.socket, "host closed");
      host.close();
    });
    // end region
  }

  // region fd exhaustion

  /** Runs the server with 96 file descriptors in another process, exhausts them, then checks it recovers. */
  static void fdExhaustion() throws Exception {
    if (!new File("/usr/bin/prlimit").exists()) {
      System.out.println("SKIP fd-exhaustion: no prlimit");
      return;
    }
    int port, legacyPort;
    try (ServerSocket a = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
         ServerSocket b = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = a.getLocalPort();
      legacyPort = b.getLocalPort();
    }
    File dir = new File("fd-exhaustion");
    dir.mkdirs();
    try (Writer config = new OutputStreamWriter(new FileOutputStream(new File(dir, "config.json")), "UTF-8")) {
      config.write("{\"bind-address\": \"127.0.0.1\", \"stream-port\": " + port +
                   ", \"max-pending-per-address\": 1000, \"connection-limit\": 0}");
    }
    String java = System.getProperty("java.home") + "/bin/java";
    Process process = new ProcessBuilder("prlimit", "--nofile=96:96", java, "-DClaj-Version=2.4.5",
                                         "-cp", System.getProperty("checks.serverClasspath"),
                                         "com.xpdustry.claj.server.Main", String.valueOf(legacyPort))
                        .directory(dir).redirectErrorStream(true).start();
    StringBuffer log = new StringBuffer();
    Thread reader = new Thread(() -> {
      try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = in.readLine()) != null) log.append(line).append('\n');
      } catch (IOException ignored) {}
    });
    reader.start();
    List<Socket> sockets = new ArrayList<>();
    try {
      InetSocketAddress relayAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
      long deadline = System.currentTimeMillis() + 10000;
      while (log.indexOf("Stream relay hosted on port") < 0) {
        require(System.currentTimeMillis() < deadline && process.isAlive(), "relay started: " + log);
        Thread.sleep(50);
      }
      for (int i = 0; i < 160; i++) {
        Socket socket = new Socket();
        socket.connect(relayAddress, TIMEOUT);
        sockets.add(socket);
      }
      Thread.sleep(500);
      require(log.indexOf("Unable to accept") >= 0, "descriptors exhausted: " + log);
      for (Socket socket : sockets) socket.close();
      sockets.clear();
      Thread.sleep(500);
      try (StreamClient client = StreamClient.connect(relayAddress, false, TIMEOUT)) {
        require(client.serverVersion() == 4, "recovered after EMFILE");
      }
      require(process.isAlive(), "relay alive");
    } catch (Throwable e) {
      System.out.println(log);
      throw e;
    } finally {
      for (Socket socket : sockets) socket.close();
      process.destroy();
      process.waitFor(5, TimeUnit.SECONDS);
      reader.join(1000);
    }
  }

  // end region
  // region helpers

  /**
   * Resets {@link ClajConfig} for a check: short stage 1-2 deadlines, and the per-address limits off, as every
   * connection comes from the loopback (as the bench does).
   */
  static void config() {
    ClajConfig.all.each(ClajConfig.Field::setDefault);
    ClajConfig.udpBatching.set(System.getProperty("checks.udp", "auto"));
    ClajConfig.registerTimeout.set(1);
    ClajConfig.controlTimeout.set(1.5f);
    ClajConfig.lingerTimeout.set(0.5f);
    ClajConfig.connectionLimit.set(0);
    ClajConfig.roomLimit.set(0);
    ClajConfig.joinLimit.set(0);
    ClajConfig.infoLimit.set(0);
    ClajConfig.listLimit.set(0);
    ClajConfig.clientRate.set(0f);
    ClajConfig.hostRate.set(0f);
    ClajConfig.afkTime.set(0);
    ClajConfig.warnClosing.set(false);
    ClajConfig.debug.set(Boolean.getBoolean("checks.debug"));
  }

  /** Keeps a host alive under {@link #idleConfig()}, in a daemon thread, until its socket closes. */
  static void pinger(HostMux host) {
    Thread thread = new Thread(() -> {
      try {
        while (true) {
          host.ping().flush();
          Thread.sleep(idleMillis() / 4);
        }
      } catch (IOException | InterruptedException | NullPointerException ignored) {}
    }, "pinger");
    thread.setDaemon(true);
    thread.start();
  }

  /** A short stage 3 idle timeout, for the timeout checks. */
  static void idleConfig() {
    ClajConfig.idleTimeout.set(0.4f);
  }

  /** {@link ClajConfig#idleTimeout}, in milliseconds. */
  static long idleMillis() {
    return (long)(ClajConfig.idleTimeout.get() * 1000);
  }

  /** Boots the server as {@link com.xpdustry.claj.server.Main} does, its 2.x port on the loopback. */
  static void boot() throws Exception {
    ClajConfig.fileName = new File("checks-config.json").getAbsolutePath();
    new File(ClajConfig.fileName).delete();
    ClajConfig.serverVersion = 4;
    ClajVars.version = com.xpdustry.claj.common.status.ClajVersion.of("2.4.5");
    ClajConfig.load();
    config();
    ClajPackets.init();
    CountDownLatch ready = new CountDownLatch(1);
    app = new ServerApplication(new ApplicationListener() {
      @Override
      public void init() {
        try {
          ClajVars.relay = server = new ClajRelay(0, true);
          InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
          server.bind(loopback, loopback);
          app.addListener(ClajVars.control = control = new ClajControl());
          Threads.daemon("CLaJ Relay", server::run);
          while (!server.isHosted()) Thread.sleep(1);
          ready.countDown();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }, 4, e -> {
      e.printStackTrace(System.out);
      System.exit(2);
    });
    require(ready.await(10, TimeUnit.SECONDS), "server booted");
  }

  /** Runs on the main thread, as the server's console and events do, and waits. */
  static void onMain(Runnable task) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    Throwable[] error = {null};
    app.post(() -> {
      try { task.run(); }
      catch (Throwable e) { error[0] = e; }
      finally { done.countDown(); }
    });
    require(done.await(10, TimeUnit.SECONDS), "main thread task done");
    if (error[0] != null) throw new RuntimeException(error[0]);
  }

  /** Runs console commands on the main thread. @return the lines they logged, without colors. */
  static List<String> console(String... commands) throws InterruptedException {
    List<String> lines = new ArrayList<>();
    onMain(() -> {
      arc.util.Log.LogHandler logger = arc.util.Log.logger;
      arc.util.Log.logger = (level, text) -> {
        lines.add(arc.util.Log.removeColors(text));
        logger.log(level, text);
      };
      try {
        for (String command : commands) control.handleCommand(command);
      } finally {
        arc.util.Log.logger = logger;
      }
    });
    return lines;
  }

  /** @return the relay's room, read on the main thread, which owns the rooms: once its current turn is over. */
  static StreamRoom room(long id) throws InterruptedException {
    StreamRoom[] room = {null};
    StreamRelay current = relay;
    if (current.started) onMain(() -> room[0] = current.room(id));
    else room[0] = current.room(id); // A driven relay, see drivenControl()
    return room[0];
  }

  static int roomCount() throws InterruptedException {
    int[] count = {0};
    StreamRelay current = relay;
    if (current.started) onMain(() -> count[0] = current.rooms().size);
    else count[0] = current.rooms().size;
    return count[0];
  }

  /** @return a 2.x peer of the server's Arc port, once the main thread registered it. */
  static ArcClient arcClient() throws Exception {
    ArcClient client = new ArcClient(server, TIMEOUT);
    boolean[] registered = {false};
    long end = System.nanoTime() + TIMEOUT * 1_000_000L;
    while (!registered[0] && System.nanoTime() < end) {
      onMain(() -> registered[0] = server.connections.containsKey(client.getID()));
      Thread.sleep(1);
    }
    require(registered[0], "Arc connection registered");
    return client;
  }

  /** Starts a relay as the server does: bound here, then driven by the main thread. */
  static void start() throws Exception {
    start(InetAddress.getLoopbackAddress());
  }

  static void start(InetAddress bind) throws Exception {
    relay = new StreamRelay(server, new InetSocketAddress(bind, 0));
    address = new InetSocketAddress(bind, relay.port());
    // -Pudp=<backend>: every check fails if it can't use that UDP backend, instead of quietly using Java's.
    String udp = System.getProperty("checks.udp");
    if (udp != null) require(relay.udp.name().equals(udp.equals("off") ? "nio" : udp), "UDP backend " + relay.udp.name());
    ClajVars.stream = relay;
    StreamRelay started = relay;
    onMain(started::start);
  }

  static void stop() throws InterruptedException {
    StreamRelay old = relay;
    relay = null;
    if (old == null) return;
    old.close();
    // Its connections leave the server's counts on the main thread: the next check starts without them.
    long end = System.nanoTime() + TIMEOUT * 1_000_000L;
    int[] left = {1};
    while (left[0] > 0 && System.nanoTime() < end) {
      onMain(() -> left[0] = old.connections.size);
      if (left[0] > 0) Thread.sleep(5);
    }
  }

  static HostMux host(RoomConfigPacket config) throws IOException {
    return StreamClient.connect(address, false, TIMEOUT).createRoom(TYPE, config);
  }

  /** A host that echoes every DATA and grants all the credit it gets back, in a daemon thread. */
  static Thread echoHost(HostMux host) {
    Thread thread = new Thread(() -> {
      // Like any host, it only sends the DATA its window allows: the rest waits for the relay's WINDOW.
      Map<Integer, Integer> credits = new HashMap<>();
      Map<Integer, ArrayDeque<byte[]>> waiting = new HashMap<>();
      try {
        while (true) {
          HostMux.Frame frame = host.read();
          if (frame.type == OPEN) host.window(frame.id, WINDOW_SIZE);
          else if (frame.type == WINDOW) credits.merge(frame.id, frame.credit(), Integer::sum);
          else if (frame.type == DATA) waiting.computeIfAbsent(frame.id, id -> new ArrayDeque<>()).add(frame.payload);

          ArrayDeque<byte[]> queue = waiting.get(frame.id);
          int credit = credits.getOrDefault(frame.id, 0);
          while (queue != null && !queue.isEmpty() && queue.peek().length <= credit) {
            byte[] payload = queue.poll();
            credit -= payload.length;
            // The client's credit comes back with its echo, so the waiting bytes stay within one window.
            host.data(frame.id, payload).window(frame.id, payload.length);
          }
          credits.put(frame.id, credit);
          host.flush();
        }
      } catch (IOException ignored) {}
    }, "echo host");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** A joined client with a tiny receive buffer, that never reads. */
  static Socket stalledClient(HostMux host) throws IOException {
    Socket socket = new Socket();
    socket.setReceiveBufferSize(1024);
    socket.connect(address, TIMEOUT);
    StreamClient client = new StreamClient(socket, false, TIMEOUT);
    require(client.join(host.roomId, (short)-1, TYPE) == null, "stalled client joined");
    return socket;
  }

  /**
   * Sends DATA to a stream that doesn't read until the relay stops returning credit, skipping
   * the frames of other streams. @return the credit the host has left, {@code 0} once stalled.
   */
  static int fill(HostMux host, int id) throws IOException {
    int credit = 0;
    host.socket.setSoTimeout(300);
    try {
      while (true) {
        while (credit > 0) {
          int length = Math.min(credit, 16384);
          host.data(id, new byte[length]);
          credit -= length;
        }
        host.flush();
        try {
          HostMux.Frame frame = host.read();
          if (frame.type == WINDOW && frame.id == id) credit += frame.credit();
        } catch (SocketTimeoutException stalled) {
          return credit;
        }
      }
    } finally {
      host.socket.setSoTimeout(TIMEOUT);
    }
  }

  static Socket raw() throws IOException {
    Socket socket = new Socket();
    socket.setSoTimeout(TIMEOUT);
    socket.connect(address, TIMEOUT);
    return socket;
  }

  static long token(Socket socket) throws IOException {
    byte[] hello = message(new DataInputStream(socket.getInputStream()), HELLO);
    return ByteBuffer.wrap(hello, 1, 8).getLong();
  }

  static DatagramPacket helloUdp(long token) {
    byte[] data = ByteBuffer.allocate(9).put((byte)HELLO_UDP).putLong(token).array();
    return new DatagramPacket(data, data.length, address);
  }

  static DatagramPacket receive(DatagramSocket socket) throws IOException {
    socket.setSoTimeout(TIMEOUT);
    DatagramPacket packet = new DatagramPacket(new byte[MAX_DATAGRAM_SIZE + 4], MAX_DATAGRAM_SIZE + 4);
    socket.receive(packet);
    return packet;
  }

  /**
   * A relay that isn't started, with one connection in stage 2 whose UDP address is {@code udp}: the check
   * calls its handlers itself, in the order it wants.
   */
  static StreamConnection drivenControl(Socket tcp, DatagramSocket udp) throws IOException {
    relay = new StreamRelay(server, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    relay.now = System.nanoTime();
    address = new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.port());
    tcp.setSoTimeout(TIMEOUT);
    tcp.connect(address, TIMEOUT);
    relay.accept();
    relay.flushDirty();
    DataInputStream in = new DataInputStream(tcp.getInputStream());
    message(in, HELLO);
    StreamConnection con = relay.connections.get(0);
    relay.welcome(con, new InetSocketAddress(InetAddress.getLoopbackAddress(), udp.getLocalPort()));
    relay.flushDirty();
    message(in, WELCOME);
    return con;
  }

  /** A {@code ROOM_CREATE} datagram: {@code u8 type + body}. */
  static byte[] createBody(int type) {
    RoomCreationRequestPacket request = new RoomCreationRequestPacket();
    request.version = 4;
    request.type = TYPE;
    ByteBuffer buffer = ByteBuffer.allocate(64).put((byte)type);
    request.write(new arc.util.io.ByteBufferOutput(buffer));
    return Arrays.copyOf(buffer.array(), buffer.position());
  }

  /** Sends a stage 2 request over the UDP association: {@code u8 type + body}. */
  static void udpRequest(StreamClient client, int type, byte[] body) throws IOException {
    byte[] data = ByteBuffer.allocate(1 + body.length).put((byte)type).put(body).array();
    client.datagrams.send(new DatagramPacket(data, data.length));
  }

  /** @return the body of the next UDP reply, which must be of that type. */
  static ByteBuffer udpReply(StreamClient client, int type) throws IOException {
    DatagramPacket packet = receive(client.datagrams);
    ByteBuffer reply = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
    int got = reply.get() & 0xff;
    require(got == type, "expected UDP reply " + type + ", got " + got);
    return reply;
  }

  static byte[] joinBody(long roomId) {
    RoomJoinRequestPacket request = new RoomJoinRequestPacket();
    request.roomId = roomId;
    request.type = TYPE;
    ByteBuffer buffer = ByteBuffer.allocate(64);
    request.write(new arc.util.io.ByteBufferOutput(buffer));
    return Arrays.copyOf(buffer.array(), buffer.position());
  }

  static byte[] frame(int type, byte[] body) {
    return ByteBuffer.allocate(3 + body.length).putShort((short)(1 + body.length)).put((byte)type).put(body).array();
  }

  static void send(Socket socket, int type, byte[] body) throws IOException {
    socket.getOutputStream().write(frame(type, body));
  }

  /** @return the body of the next stage 1-2 message, which must be of that type. */
  static byte[] message(DataInputStream in, int type) throws IOException {
    int length = in.readUnsignedShort();
    int got = in.readUnsignedByte();
    byte[] body = new byte[length - 1];
    in.readFully(body);
    require(got == type, "expected message " + type + ", got " + got);
    return body;
  }

  /** Requires the host to get a {@code CLOSE error} for that stream, skipping credit returned to others. */
  static void requireKicked(HostMux host, int id, String what) throws IOException {
    host.socket.setSoTimeout(TIMEOUT);
    HostMux.Frame frame;
    do frame = host.read();
    while (frame.type == WINDOW && frame.id != id);
    require(frame.type == CLOSE && frame.id == id && frame.reason() == DcReason.error,
            what + ": CLOSE error for that client only, got " + frame);
  }

  static void requireClosed(Socket socket, String what) throws IOException {
    socket.setSoTimeout(TIMEOUT);
    try {
      InputStream in = socket.getInputStream();
      while (in.read() >= 0) {} // Pending bytes, e.g. a reply before the close.
    } catch (SocketTimeoutException e) {
      throw new AssertionError(what + ": still open");
    } catch (SocketException reset) {
      // A reset is a close too.
    }
  }

  /** Sets a value its field refuses, as a corrupt state would. */
  static <T> void force(ClajConfig.Field<T> field, T value) throws ReflectiveOperationException {
    Field f = ClajConfig.Field.class.getDeclaredField("value");
    f.setAccessible(true);
    f.set(field, value);
  }

  static Object arrayOf(ByteRing ring) throws ReflectiveOperationException {
    Field field = ByteRing.class.getDeclaredField("bytes");
    field.setAccessible(true);
    return field.get(ring);
  }

  static void require(boolean condition, String what) {
    if (!condition) throw new AssertionError(what);
  }

  // end region

  private RelayChecks() {}
}
