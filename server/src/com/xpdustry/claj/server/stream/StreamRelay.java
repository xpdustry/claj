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
import static com.xpdustry.claj.server.stream.StreamConnection.*;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.net.DcReason;
import arc.net.Server.ServerConnectFilter;
import arc.struct.LongMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.ByteBufferOutput;

import com.xpdustry.claj.common.packets.Packet;
import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.status.CloseReason;
import com.xpdustry.claj.common.util.AddressUtil;
import com.xpdustry.claj.server.*;


/**
 * The stream relay: one selector, a TCP listener and a UDP socket on the same port, driven by the main thread
 * while it waits for tasks and between their batches (see {@link ServerApplication.Waiter}), so the rooms, events
 * and routines it shares with {@link ClajRelay} have one owner. <br>
 * Credit based, with a memory budget; an error closes its peer, or its room for a host, never the loop.
 * See {@code stream-notes/stream-relay.md}.
 */
public class StreamRelay implements Closeable, ApplicationListener, ServerApplication.Waiter {
  /** No deadline. */
  public static final long NONE = Long.MAX_VALUE;
  /** Period of the deadline sweep, which is also the longest select wait. */
  public static final long SWEEP_MILLIS = 100;
  /** Pause of the listener after a failed accept, e.g. when the process is out of file descriptors. */
  public static final long ACCEPT_BACKOFF_MILLIS = 100;
  /** Bounds a burst of accepts, so that the other sockets get their turn. */
  public static final int MAX_ACCEPTS_PER_TURN = 64;
  /** Reserved for a stage 1-2 connection: its request buffer and the usual replies. */
  public static final int PENDING_RESERVE = 2048;
  /** Frames the relay may queue for one stream past the host queue size: OPEN, WINDOW, FIN and CLOSE. */
  public static final int FRAMES_PER_STREAM = 4 * HEADER_SIZE + OPEN_SIZE + WINDOW_PAYLOAD_SIZE + CLOSE_PAYLOAD_SIZE;
  public static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
  /** Longest keepalive idle time Linux takes, in seconds. */
  public static final int MAX_KEEP_ALIVE_IDLE = 32767;
  /** Keepalive probes before a dead peer is dropped. */
  public static final int KEEP_ALIVE_COUNT = 3;
  /** Slices a host queue holds before the streams of its room wait for their turn. */
  public static final int HOST_QUEUE_SLICES = 4;
  /** {@code TCP_NOTSENT_LOWAT}: about what a client that doesn't read holds in the kernel, 32 KiB without it. */
  public static final int NOT_SENT_LOWAT = 16 * 1024;

  /** The control plane: rooms, connections, events and routines of both transports. */
  public final ClajRelay server;
  public final long budget;
  /** {@link ClajConfig#sliceSize} and {@link #HOST_QUEUE_SLICES} of it, read at start as the reservations use them. */
  public final int sliceSize, hostQueueSize;
  /** Queue limit of a host without streams: the host queue, one slice past it and a control frame. */
  protected final int hostQueueLimit;
  /** Reserved for a room: the queue of its host and the buffer of its split frames. */
  protected final int hostReserve;
  /** Reserved for a stream: its window and the frames of its stream in the host queue. */
  protected final int streamReserve;
  protected final StreamControl control;
  protected final SecureRandom random = new SecureRandom();

  protected final Selector selector;
  protected final ServerSocketChannel listener;
  protected final DatagramChannel datagrams;
  protected final SelectionKey listenerKey, datagramKey;
  /** UDP I/O on {@link #datagrams}, batched or not. */
  protected final Datagrams udp;
  protected volatile boolean running = true, started;
  /** Last connection id given, they go down from {@code -1}, skipping the ones taken, see {@link #nextId()}. */
  protected int lastUid;

  /** Every open connection, for the deadline sweep. */
  public final Seq<StreamConnection> connections = new Seq<>();
  /** Connections in stage 1, by hello token. */
  protected final LongMap<StreamConnection> tokens = new LongMap<>();
  /** Connections with bytes queued during this loop turn, written at its end. */
  protected StreamConnection dirtyHead;
  /** Bytes reserved in the budget. */
  protected long reserved;

  /** Shared read buffer: host frames and datagrams are parsed from it, never left in it. */
  protected final ByteBuffer scratch = ByteBuffer.allocate(64 * 1024);
  /** Stage 1-2 message being written, type included. */
  protected final ByteBuffer reply = ByteBuffer.allocate(MAX_REPLY_SIZE);
  protected final ByteBufferOutput output = new ByteBufferOutput(reply);
  /** Whether the request being handled came over UDP, so {@link #send} replies with a datagram. */
  protected boolean udpRequest;

  /** Java 11's {@code Selector.select(Consumer, long)}, without the selected-key set; {@code null} on Java 8. */
  public static final MethodHandle SELECT_EACH =
      selectorMethod("select", MethodType.methodType(int.class, Consumer.class, long.class));
  /** Java 11's {@code Selector.selectNow(Consumer)}; {@code null} on Java 8. */
  public static final MethodHandle SELECT_NOW_EACH =
      selectorMethod("selectNow", MethodType.methodType(int.class, Consumer.class));
  protected final Consumer<SelectionKey> onReady = this::dispatch;

  /** {@code System.nanoTime()} of the current loop turn, read when the select returns. */
  protected long now;
  /** Whether {@link #now} was read since the last select returned. */
  protected boolean timed;
  /** Whether libclaj may still set the keepalive delays and {@code TCP_NOTSENT_LOWAT}: off at the first refusal. */
  protected boolean nativeKeepAlive = true, notSentLowat = true;
  protected long lastSweep, acceptResumeAt = NONE;

  /** Binds the port; {@link ClajConfig} must be loaded, its fields check their values. */
  public StreamRelay(ClajRelay server, InetSocketAddress address) throws IOException {
    this.server = server;
    long memory = ClajConfig.memoryBudget.get();
    budget = memory > 0 ? memory : Runtime.getRuntime().maxMemory() / 2;
    sliceSize = ClajConfig.sliceSize.get();
    hostQueueSize = HOST_QUEUE_SLICES * sliceSize;
    hostQueueLimit = hostQueueSize + HEADER_SIZE + sliceSize + HEADER_SIZE + MAX_CONTROL_SIZE;
    hostReserve = hostQueueLimit + Math.max(MAX_CONTROL_SIZE, MAX_DATAGRAM_SIZE);
    streamReserve = WINDOW_SIZE + FRAMES_PER_STREAM;
    control = new StreamControl(this);

    // Java 8 lazily opens a socket pair on the first close: do it now, not when out of file descriptors.
    SocketChannel.open().close();
    selector = Selector.open();
    ServerSocketChannel tcp = null;
    DatagramChannel udp = null;
    try {
      // With port 0, the TCP port the system picks can be taken for UDP: then try another one.
      for (int attempt = 1;; attempt++) {
        tcp = ServerSocketChannel.open();
        tcp.configureBlocking(false);
        tcp.bind(address, 1024);
        int port = ((InetSocketAddress)tcp.getLocalAddress()).getPort();
        udp = DatagramChannel.open();
        udp.configureBlocking(false);
        try {
          udp.bind(new InetSocketAddress(address.getAddress(), port));
          break;
        } catch (BindException e) {
          if (address.getPort() != 0 || attempt == 8) throw e;
          tcp.close();
          udp.close();
        }
      }
      listenerKey = tcp.register(selector, SelectionKey.OP_ACCEPT);
      datagramKey = udp.register(selector, SelectionKey.OP_READ);
    } catch (IOException | RuntimeException e) {
      if (tcp != null) tcp.close();
      if (udp != null) udp.close();
      selector.close();
      throw e;
    }
    listener = tcp;
    datagrams = udp;
    this.udp = Datagrams.open(this, udp, ClajConfig.udpBatching.get());
  }

  /** @return the longest idle time {@link #tune} draws, 50% above {@link ClajConfig#keepAlive}, at most Linux's. */
  public static int maxKeepAliveIdle() {
    int idle = ClajConfig.keepAlive.get();
    return Math.min(MAX_KEEP_ALIVE_IDLE, idle + idle / 2);
  }

  /** @return seconds of a {@link ClajConfig} timeout, in nanoseconds. */
  public static long nanos(float seconds) {
    return (long)(seconds * 1e9);
  }

  public int port() {
    return listener.socket().getLocalPort();
  }

  /** {@link Log#info}, unless {@link ClajRelay#beQuiet}, as the server's own logs. */
  public void info(String text, Object... args) {
    if (!server.beQuiet) Log.info(text, args);
  }

  /** {@link Log#warn}, unless {@link ClajRelay#beQuiet}, as the server's own logs. */
  public void warn(String text, Object... args) {
    if (!server.beQuiet) Log.warn(text, args);
  }

  /** @return a new stream room, for {@link ClajRelay#newRoom}. */
  public StreamRoom newRoom(long id, ClajConnection host, ClajType type) {
    return new StreamRoom(this, id, host, type);
  }

  /** @return the room of this relay, {@code null} if none. */
  public StreamRoom room(long id) {
    return server.getRoom(id) instanceof StreamRoom room && room.relay == this ? room : null;
  }

  /** @return the open rooms of this relay. */
  public Seq<StreamRoom> rooms() {
    Seq<StreamRoom> rooms = new Seq<>();
    for (ClajRoom room : server.rooms.values()) {
      if (room instanceof StreamRoom r && r.relay == this) rooms.add(r);
    }
    return rooms;
  }

  // region loop

  @Override
  public void init() {
    Events.run(ClajEvents.ServerLoadedEvent.class, this::start);
  }

  @Override
  public void dispose() {
    close();
  }

  /** Makes the main thread drive the relay, see {@link ServerApplication.Waiter}. Main thread only. */
  public void start() {
    if (started) throw new IllegalStateException("already started");
    started = true;
    ((ServerApplication)Core.app).setWaiter(this);
    Log.info("Stream relay hosted on port @ (TCP and UDP), memory budget @ MiB, UDP @, libclaj @.", port(),
             budget >> 20, udp.name(), LibClaj.available() ? LibClaj.target() : "unavailable");
  }

  /** Stops the relay: rooms are told the server is closing, then every socket is closed. Waits for the main thread. */
  @Override
  public void close() {
    if (!started || Core.app == null || Thread.currentThread() == Core.app.getMainThread()) {
      stop();
      return;
    }
    CountDownLatch done = new CountDownLatch(1);
    Core.app.post(() -> {
      stop();
      done.countDown();
    });
    try { done.await(10, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  protected void stop() {
    if (!running) return;
    running = false;
    if (started && Core.app instanceof ServerApplication app && app.getWaiter() == this) app.setWaiter(null);
    shutdown();
  }

  protected static MethodHandle selectorMethod(String name, MethodType type) {
    try {
      return MethodHandles.publicLookup().findVirtual(Selector.class, name, type);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Relays for up to {@code millis}, or until a task is posted to the main thread. */
  @Override
  public void await(long millis) {
    long end = System.nanoTime() + millis * 1_000_000L;
    ServerApplication app = (ServerApplication)Core.app;
    try {
      // A task posted during a select wakes it up, see wakeup().
      while (running && !app.hasTasks()) {
        long wait = Math.min(SWEEP_MILLIS, (end - now) / 1_000_000L);
        if (wait <= 0) break;
        turn(wait);
      }
    } catch (Throwable e) {
      crashed(e);
    }
  }

  /** One turn without waiting, between two batches of tasks: the tasks never hold the sockets back. */
  @Override
  public void poll() {
    if (!running) return;
    try {
      turn(0);
    } catch (Throwable e) {
      crashed(e);
    }
  }

  /** Per peer errors are handled where they happen (handle, flush, datagram, sweep): only the selector gets here. */
  protected void crashed(Throwable error) {
    Log.err("Stream relay stopped by an unexpected error", error);
    stop();
  }

  @Override
  public void wakeup() {
    selector.wakeup();
  }

  /** One loop turn: ready keys, the sweep, then the writes the turn queued. {@code wait} 0 doesn't wait. */
  protected void turn(long wait) throws Throwable {
    timed = false;
    if (SELECT_EACH != null) { // The exact types, return included.
      int ready = wait > 0 ? (int)SELECT_EACH.invokeExact(selector, onReady, wait) :
                             (int)SELECT_NOW_EACH.invokeExact(selector, onReady);
    } else {
      if (wait > 0) selector.select(wait);
      else selector.selectNow();
      // The Java 8 selector API allocates one iterator per batch, never per frame.
      Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
      while (keys.hasNext()) {
        SelectionKey key = keys.next();
        keys.remove();
        dispatch(key);
      }
    }
    if (!timed) now = System.nanoTime();
    if (now - lastSweep >= SWEEP_MILLIS * 1_000_000L) sweep();
    flushDirty();
    udp.flush();
  }

  protected void dispatch(SelectionKey key) {
    if (!timed) {
      now = System.nanoTime();
      timed = true;
    }
    if (key == listenerKey) accept();
    else if (key == datagramKey) receive();
    else handle(key);
  }

  protected void handle(SelectionKey key) {
    StreamConnection con = (StreamConnection)key.attachment();
    try {
      if (key.isValid() && key.isWritable()) write(con);
      if (key.isValid() && key.isReadable()) read(con);
    } catch (IOException | RuntimeException | Error e) {
      failed(con, e);
    }
  }

  /** Writes what the loop turn queued, clients first so the credit they return rides in their host's write. */
  protected void flushDirty() {
    while (dirtyHead != null) {
      StreamConnection hosts = null;
      while (dirtyHead != null) {
        StreamConnection con = dirtyHead;
        dirtyHead = con.nextDirty;
        if (con.stage == STAGE_HOST) { // Kept dirty: a credit queued meanwhile doesn't list it twice.
          con.nextDirty = hosts;
          hosts = con;
          continue;
        }
        flush(con);
      }
      while (hosts != null) {
        StreamConnection host = hosts;
        hosts = host.nextDirty;
        flush(host);
      }
    }
  }

  protected void flush(StreamConnection con) {
    con.nextDirty = null;
    con.dirty = false;
    if (con.dead || con.out.isEmpty()) return;
    try {
      write(con);
    } catch (IOException | RuntimeException | Error e) {
      failed(con, e);
    }
  }

  /** The error policy of every peer: close it, or its room for a host, never the loop. */
  protected void failed(StreamConnection con, Throwable error) {
    con.closeReason = DcReason.error;
    try {
      if (error instanceof ProtocolException) violation(con, error.getMessage());
      else if (error instanceof IOException) {
        Log.debug("@: @", con, error);
        close(con);
      } else {
        Log.err("Error while handling " + con + ", closing it", error);
        close(con);
      }
    } catch (RuntimeException | Error e) {
      // The close itself failed: drop the whole room, without telling anyone, rather than stop the loop.
      Log.err("Unable to close " + con + ", closing its room", e);
      StreamRoom room = con.room;
      try {
        if (room != null) server.closeRoom(room);
        else close(con);
      } catch (RuntimeException | Error again) {
        Log.err("Unable to close the room of " + con, again);
      }
    }
  }

  protected void sweep() {
    lastSweep = now;
    idleNanos = nanos(ClajConfig.idleTimeout.get());
    if (acceptResumeAt != NONE && now - acceptResumeAt >= 0) {
      acceptResumeAt = NONE;
      listenerKey.interestOps(SelectionKey.OP_ACCEPT);
    }
    for (int i=connections.size - 1; i>=0; i--) {
      if (i >= connections.size) continue; // A close may have removed several connections.
      StreamConnection con = connections.get(i);
      try {
        if (con.throttled && allowance(con) > 0) {
          con.throttled = false;
          interests(con);
        }
        expire(con);
      } catch (RuntimeException | Error e) {
        failed(con, e);
      }
    }
  }

  /** Closes the connection if its deadline passed, or if it is idle. */
  protected void expire(StreamConnection con) {
    if (con.deadline == NONE) {
      if (idle(con)) {
        Log.debug("@: idle timeout", con);
        con.closeReason = DcReason.timeout;
        close(con);
      }
      return;
    }
    if (now - con.deadline < 0) return;
    Log.debug("@: @ timeout", con, con.closing ? "linger" : con.stage == STAGE_HELLO ? "hello" : "control");
    close(con);
  }

  /** {@link ClajConfig#idleTimeout} in nanoseconds, read by each sweep. */
  protected long idleNanos;

  /** Whether nothing was received from a stage 3 peer within {@code idleTimeout}, credit waits excepted. */
  protected boolean idle(StreamConnection con) {
    if (idleNanos <= 0 || con.closing || con.throttled || con.stage < STAGE_CLIENT) return false;
    if (con.stage == STAGE_CLIENT && (con.ready || con.readAhead >= 0)) return false;
    return now - con.lastReceived > idleNanos;
  }

  protected void shutdown() {
    if (listener.isOpen()) {
      now = System.nanoTime();
      for (StreamRoom room : rooms()) {
        try { server.closeRoom(room, CloseReason.serverClosed); }
        catch (RuntimeException | Error e) { Log.err("Unable to close the room " + room, e); }
      }
      // Best effort: one write per connection, a stopping server doesn't wait for slow peers.
      while (!connections.isEmpty()) {
        StreamConnection con = connections.get(connections.size - 1);
        try { con.out.write(con.socket); }
        catch (IOException ignored) {}
        close(con);
      }
    }
    try {
      listener.close();
      datagrams.close();
      selector.close();
    } catch (IOException e) {
      Log.err("Unable to close the stream relay sockets", e);
    }
  }

  // end region
  // region lifecycle

  protected void accept() {
    for (int i=0; i<MAX_ACCEPTS_PER_TURN; i++) {
      SocketChannel socket;
      try {
        socket = listener.accept();
      } catch (IOException e) {
        // Usually EMFILE: back off without stopping the rooms, the sweep resumes accepting.
        Log.warn("Unable to accept a connection, retrying in @ms: @", ACCEPT_BACKOFF_MILLIS, e.getMessage());
        listenerKey.interestOps(0);
        acceptResumeAt = now + ACCEPT_BACKOFF_MILLIS * 1_000_000L;
        return;
      }
      if (socket == null) return;
      try {
        admit(socket);
      } catch (IOException | RuntimeException | Error e) {
        // Before the connection exists: only the socket to close. Later failures are handled in admit().
        Log.debug("Unable to admit a connection: @", e);
        try { socket.close(); }
        catch (IOException ignored) {}
      }
    }
  }

  protected void admit(SocketChannel socket) throws IOException {
    InetSocketAddress remote = (InetSocketAddress)socket.getRemoteAddress();
    InetAddress address = remote.getAddress();
    String ip = address.getHostAddress();
    // Before any buffer: the server's filter (the blacklist), the stream's own caps, then the server's caps.
    ServerConnectFilter filter = server.getConnectFilter();
    if (filter != null && !filter.accept(ip) || reserved + PENDING_RESERVE > budget) {
      socket.close();
      return;
    }
    String refusal = server.refusal(address, ip); // Counts the address if accepted
    if (refusal != null) {
      socket.close();
      warn("Stream connection (@) rejected " + refusal, ip);
      return;
    }
    StreamConnection con = new StreamConnection(this, socket, remote, nextId(), now);
    con.claj = new ClajConnection(con, address, ip, AddressUtil.encodeId(con));
    con.setArbitraryData(con.claj);
    con.setName("Stream connection " + con.claj.sid);
    try {
      socket.configureBlocking(false);
      socket.socket().setTcpNoDelay(true);
      tune(socket);
      con.key = socket.register(selector, 0, con);
    } catch (IOException | RuntimeException e) {
      server.uncountStream(con.claj);
      throw e;
    }
    con.slot = connections.size;
    connections.add(con);
    try {
      server.onConnect(con.claj);
      reserve(con, PENDING_RESERVE);
      do con.token = random.nextLong();
      while (con.token == 0 || tokens.containsKey(con.token));
      tokens.put(con.token, con);
      // The hello must come within the 2.x registration time, as Arc's registration.
      int hello = ClajConfig.registerTimeout.get();
      float control = ClajConfig.controlTimeout.get();
      con.deadline = now + nanos(hello > 0 ? Math.min(hello, control) : control);

      reply(HELLO).writeByte(VERSION);
      output.writeLong(con.token);
      send(con);
    } catch (RuntimeException | Error e) {
      failed(con, e);
    }
  }

  /**
   * @return an id that no registered connection of either transport has. Arc draws its own at random, so one
   * can still meet a stream id later: {@link ClajRelay#onConnect} refuses that Arc connection.
   */
  protected int nextId() {
    int id;
    do id = --lastUid;
    while (id == ClajRoom.CON_BROADCAST || server.connections.containsKey(id));
    return id;
  }

  /**
   * TCP keepalive with a random idle time, so sockets idle together aren't probed together, and
   * {@code TCP_NOTSENT_LOWAT}. A refusal by libclaj logs one warning, never refuses the connection.
   */
  protected void tune(SocketChannel socket) throws IOException {
    int fd = Sockets.fd(socket);
    int keepAlive = ClajConfig.keepAlive.get();
    if (keepAlive > 0) {
      int max = maxKeepAliveIdle();
      int idle = keepAlive >= max ? keepAlive : keepAlive + ThreadLocalRandom.current().nextInt(max - keepAlive + 1);
      int error = Sockets.keepAlive(socket, nativeKeepAlive ? fd : -1, idle, Math.max(1, keepAlive / 2),
                                    KEEP_ALIVE_COUNT);
      if (error < 0) {
        nativeKeepAlive = false;
        Log.warn("libclaj can't set the keepalive delays (errno @), using the runtime's options", -error);
      }
    }
    if (notSentLowat && fd >= 0) {
      int error = LibClaj.setsockopt(fd, LibClaj.IPPROTO_TCP, LibClaj.TCP_NOTSENT_LOWAT, NOT_SENT_LOWAT);
      if (error < 0) {
        notSentLowat = false;
        Log.warn("TCP_NOTSENT_LOWAT unavailable (errno @), not used", -error);
      }
    }
  }

  /** The only way out for a connection: detaches it from everything it is part of. */
  public void close(StreamConnection con) {
    if (con.dead) return;
    con.dead = true;
    con.key.cancel();
    try { con.socket.close(); }
    catch (IOException ignored) {}

    int last = connections.size - 1;
    StreamConnection moved = connections.pop();
    if (con.slot != last) {
      connections.set(con.slot, moved);
      moved.slot = con.slot;
    }
    if (con.token != 0) tokens.remove(con.token);
    udp.dissociate(con);
    reserved -= con.reserved;
    con.reserved = 0;
    con.out.discard();
    // The server's side: its room (a host closes it, a client gets a CLOSE to its host), events and caches.
    // It is done even if a plugin throws, so the error stops here: the loop and the other peers go on.
    server.uncountStream(con.claj);
    try {
      server.onDisconnect(con.claj, con.closeReason);
    } catch (RuntimeException | Error e) {
      Log.err("Error while disconnecting " + con, e);
    }
  }

  /** {@link ClajRoom#notifyLeft} of a stream: {@code CLOSE} to the host, unless the stream ended both ways. */
  protected void left(StreamRoom room, StreamConnection con, DcReason reason) {
    if (room.mux.dead || room.mux.closing) return;
    StreamConnection host = room.mux;
    try {
      if (!con.isFinished()) {
        // Like the ConnectionClosedPacket of the 2.x server: the host can tell a timeout from an error.
        frame(host, CLOSE, con.conId, CLOSE_PAYLOAD_SIZE);
        host.out.put((reason == null ? DcReason.closed : reason).ordinal());
      }
      interests(host);
    } catch (RuntimeException | Error e) { // Its queue may hold half a frame now.
      Log.err("Unable to tell " + host + " that " + con + " left, closing the room", e);
      host.closeReason = DcReason.error;
      host.out.discard();
      Core.app.post(() -> close(host)); // Not from inside the room's own removal.
    }
  }

  /** Closes gracefully: writes the queue, shuts the output down, then discards the input until EOF, without reset. */
  protected void linger(StreamConnection con) {
    if (con.dead || con.closing) return;
    con.closing = true;
    con.deadline = now + nanos(ClajConfig.lingerTimeout.get());
    if (con.out.isEmpty()) drain(con);
    else interests(con);
  }

  /** Lingering, after the last queued byte: shuts the output down and waits for the peer's EOF. */
  protected void drain(StreamConnection con) {
    try {
      if (!con.outputShut) con.socket.shutdownOutput();
    } catch (IOException e) {
      close(con);
      return;
    }
    con.outputShut = true;
    con.draining = true;
    interests(con);
  }

  /** Reads and discards the input of a draining connection: its stream is over. */
  protected void discard(StreamConnection con) throws IOException {
    scratch.clear();
    if (con.socket.read(scratch) < 0) close(con);
  }

  protected void violation(StreamConnection con, String reason) {
    Log.debug("@: protocol violation: @", con, reason);
    close(con);
  }

  // end region
  // region budget

  /** @return whether the budget covers {@code bytes} more, which are then reserved for {@code con}. */
  protected boolean reserve(StreamConnection con, int bytes) {
    if (reserved + bytes > budget) return false;
    reserved += bytes;
    con.reserved += bytes;
    return true;
  }

  protected void unreserve(StreamConnection con, int bytes) {
    reserved -= bytes;
    con.reserved -= bytes;
  }

  // end region
  // region stage 1-2

  protected void read(StreamConnection con) throws IOException {
    if (con.closing) {
      if (con.draining) discard(con);
      return;
    }
    switch (con.stage) {
      case STAGE_HELLO:
      case STAGE_CONTROL:
        readRequests(con);
        break;
      case STAGE_CLIENT:
        if (con.upCredit == 0) {
          readAhead(con);
          break;
        }
        // Readiness only: the bytes are read on the stream's turn, straight into the host queue.
        con.room.offer(con);
        interests(con);
        pump(con.room);
        break;
      case STAGE_HOST:
        hostRead(con);
        break;
    }
  }

  /** Reads what the TCP socket holds of stage 1-2 requests, and handles the complete ones. */
  protected void readRequests(StreamConnection con) throws IOException {
    if (con.request == null) con.request = ByteBuffer.allocate(LENGTH_SIZE + MAX_REQUEST_SIZE);
    if (con.socket.read(con.request) < 0) close(con);
    else processRequests(con);
  }

  /** Handles the complete requests, one at a time: the next waits until the previous reply is written. */
  protected void processRequests(StreamConnection con) throws ProtocolException {
    ByteBuffer in = con.request;
    if (in == null) return;
    in.flip();
    while (con.isPending() && !con.dead && !con.closing && con.out.isEmpty() && in.remaining() >= LENGTH_SIZE) {
      int length = in.getShort(in.position()) & 0xffff;
      if (length < 1 || length > MAX_REQUEST_SIZE) throw new ProtocolException("invalid request length " + length);
      if (in.remaining() < LENGTH_SIZE + length) break;
      int start = in.position() + LENGTH_SIZE, end = start + length, limit = in.limit();
      in.limit(end).position(start);
      int type = in.get() & 0xff;
      try {
        request(con, type, in, limit > end);
      } catch (RuntimeException e) {
        throw new ProtocolException("malformed request " + type + ": " + e);
      }
      if (con.dead) return;
      if (in.hasRemaining()) throw new ProtocolException("trailing bytes in request " + type);
      in.limit(limit).position(end);
    }
    if (con.dead) return;
    if (con.isPending()) in.compact();
    else con.request = null;
    interests(con);
  }

  /** @param pipelined whether bytes follow this request, which a transition request forbids. */
  protected void request(StreamConnection con, int type, ByteBuffer body, boolean pipelined) throws ProtocolException {
    if (con.stage == STAGE_HELLO) {
      if (type != HELLO_TCP || body.remaining() != Long.BYTES || body.getLong() != con.token)
        throw new ProtocolException("invalid hello");
      welcome(con, null);
      return;
    }
    // Every request passes here, over TCP or UDP (see udpRequest).
    if (++con.requests > ClajConfig.maxRequests.get()) throw new ProtocolException("too many requests");
    if (!server.checkRateLimit(con.claj, Math.max(body.remaining() + 1, ClajRelay.MIN_PACKET_COST))) {
      // The server's rate limit, a request is a packet: kicked, after its last reply
      body.position(body.limit());
      return;
    }
    control.request(con, type, body, pipelined);
  }

  /** Ends stage 1. The token is spent, and {@code udp} (if any) now identifies the connection. */
  protected void welcome(StreamConnection con, InetSocketAddress udp) {
    tokens.remove(con.token);
    con.token = 0;
    if (udp != null) this.udp.associate(con, udp);
    con.stage = STAGE_CONTROL;
    con.deadline = con.acceptedAt + nanos(ClajConfig.controlTimeout.get());
    reply(WELCOME).writeByte(udp == null ? 0 : FLAG_UDP);
    queue(con);
  }

  /** Clears the reply buffer and writes the message type. @return the output to write the body. */
  protected ByteBufferOutput reply(int type) {
    reply.clear();
    reply.put((byte)type);
    return output;
  }

  protected ByteBufferOutput reply(int type, Packet packet) {
    reply(type);
    packet.write(output);
    return output;
  }

  /** Sends the reply buffer on the transport of the request being handled, see {@link #udpRequest}. */
  protected void send(StreamConnection con) {
    if (udpRequest) {
      reply.flip();
      udp.send(con, reply);
    } else queue(con);
  }

  /** Queues the reply buffer as a stage 1-2 message on TCP, whatever the transport of the request. */
  protected void queue(StreamConnection con) {
    reply.flip();
    int length = reply.remaining();
    con.out.putShort(length);
    con.out.put(reply, length);
    interests(con);
  }

  /**
   * Sends a packet of {@link ClajRelay} or {@link ClajRoom} to a stream connection, see
   * {@link StreamConnection#sendTCP}: a stage 2 reply, on the transport of the request, or the {@code ROOM_CLOSED}
   * of a host. Other objects have no stream wire.
   * @return the bytes queued or sent.
   */
  public int send(StreamConnection con, Object object) {
    if (!(object instanceof Packet packet) || con.dead || con.closing) return 0;
    int type = typeOf(packet);
    if (type == 0) return 0;
    // A new host gets its ROOM_CREATED once in stage 3, see ClajRoom#create(), then only ROOM_CLOSED in CONTROL.
    if (con.stage == STAGE_HOST && type != ROOM_CREATED) {
      if (type != ROOM_CLOSED) return 0;
      reply(type, packet);
      return control(con.room);
    } else if (con.stage == STAGE_CLIENT) return 0;
    reply(type, packet);
    int length = reply.position();
    // The accepts stay in the TCP stream, right where it switches to stage 3.
    if (type == JOIN_ACCEPTED || type == ROOM_CREATED) queue(con);
    else send(con);
    return length;
  }

  /** Queues the reply buffer to the host in a {@code CONTROL} frame. @return the payload length, 0 if dropped. */
  protected int control(StreamRoom room) {
    reply.flip();
    int length = reply.remaining();
    StreamConnection host = room.mux;
    if (host.out.free() < HEADER_SIZE + length) return 0; // The host is too far behind to be told.
    frame(host, CONTROL, ROOM, length);
    host.out.put(reply, length);
    interests(host);
    return length;
  }

  /** Starts the stream of an accepted client, see {@link StreamRoom#setRoom}. The accept is queued. */
  protected void startClient(StreamConnection con, StreamRoom room) {
    leavePending(con);
    con.stage = STAGE_CLIENT;
    con.lastReceived = now;
    con.room = room;
    con.conId = ++room.lastId;
    con.downCredit = WINDOW_SIZE;
    con.controlBytes = con.out.size();
    con.out.limit = con.controlBytes + WINDOW_SIZE;
    room.streams.put(con.conId, con);
    room.open.add(con);
    room.mux.out.limit += FRAMES_PER_STREAM;
    interests(con);
  }

  /** {@link ClajRoom#notifyJoined} of a stream: {@code OPEN} and the first credit to the host. */
  protected void open(StreamRoom room, StreamConnection con) {
    StreamConnection host = room.mux;
    frame(host, OPEN, con.conId, OPEN_SIZE);
    host.out.put(con.udp == null ? 0 : FLAG_UDP);
    host.out.putLong(AddressUtil.hash(con.address));
    frame(host, WINDOW, con.conId, WINDOW_PAYLOAD_SIZE);
    host.out.putInt(WINDOW_SIZE);
    interests(host);
  }

  /** Makes the connection the host of a new room, see {@link StreamRoom#setRoom}. Its reply comes next. */
  protected void startHost(StreamConnection con, StreamRoom room) {
    leavePending(con);
    con.stage = STAGE_HOST;
    con.lastReceived = now;
    con.room = room;
    con.out.limit = con.out.size() + LENGTH_SIZE + 1 + Long.BYTES + hostQueueLimit; // ROOM_CREATED, then the mux
    interests(con);
  }

  protected void leavePending(StreamConnection con) {
    con.deadline = NONE;
  }

  // end region
  // region writing

  protected void write(StreamConnection con) throws IOException {
    int written = con.out.write(con.socket);
    con.writeBlocked = !con.out.isEmpty();
    if (con.closing) {
      // A short write must arm OP_WRITE: flushDirty() already took the connection off its list.
      if (con.out.isEmpty()) drain(con);
      else interests(con);
      return;
    }
    switch (con.stage) {
      case STAGE_HELLO:
      case STAGE_CONTROL:
        if (!con.out.isEmpty()) break;
        // Large replies are rare, don't keep their buffer.
        con.out.release();
        unreserve(con, con.replyReserved);
        con.replyReserved = 0;
        processRequests(con);
        break;
      case STAGE_CLIENT:
        clientWritten(con, written);
        break;
      case STAGE_HOST:
        pump(con.room);
        break;
    }
    interests(con);
  }

  /** Returns the credit of the stream bytes written to a client, and applies a pending host {@code FIN}. */
  protected void clientWritten(StreamConnection client, int written) throws IOException {
    int data = written;
    if (client.controlBytes > 0) {
      int replies = Math.min(client.controlBytes, data);
      client.controlBytes -= replies;
      data -= replies;
    }
    if (client.out.isEmpty() && client.hostFin) {
      shutdownOutput(client);
      return;
    }
    if (data == 0 || client.hostFin) return;
    client.returnCredit += data;
    if (creditDue(client)) {
      client.room.creditPending = true;
      pump(client.room);
    }
  }

  /** Whether a client's written bytes return their credit now: once half a window, or once its queue is empty. */
  protected static boolean creditDue(StreamConnection client) {
    return client.returnCredit >= WINDOW_SIZE / 2 || client.returnCredit > 0 && client.out.isEmpty();
  }

  protected void shutdownOutput(StreamConnection client) throws IOException {
    if (client.outputShut) return;
    client.outputShut = true;
    client.socket.shutdownOutput();
    if (client.isFinished()) close(client);
  }

  /** Queues a mux frame header to a host. The payload, if any, must follow. */
  protected void frame(StreamConnection host, int type, int id, int length) {
    ByteRing out = host.out;
    out.put(type);
    out.putInt(id);
    out.putShort(length);
  }

  // end region
  // region upstream

  /** Fills the host queue: returned credits first, then one slice per ready client, in turn. */
  protected void pump(StreamRoom room) {
    if (room.isClosed()) return;
    StreamConnection host = room.mux;
    int limit = hostQueueSize;
    if (room.creditPending && host.out.size() < limit) flushCredits(room);
    StreamConnection client;
    while (host.out.size() < limit && (client = room.poll()) != null) turn(room, client);
    interests(host);
  }

  /** Coalesced credit returns: at most one {@code WINDOW} per stream waits in the host queue. */
  protected void flushCredits(StreamRoom room) {
    room.creditPending = false;
    ByteRing out = room.mux.out;
    StreamConnection[] clients = room.open.items;
    for (int i=0; i<room.open.size; i++) {
      StreamConnection client = clients[i];
      if (!creditDue(client)) continue;
      if (out.size() >= hostQueueSize) {
        room.creditPending = true;
        return;
      }
      frame(room.mux, WINDOW, client.conId, WINDOW_PAYLOAD_SIZE);
      out.putInt(client.returnCredit);
      client.downCredit += client.returnCredit;
      client.returnCredit = 0;
    }
  }

  /** Reads one slice of a client straight into the host queue, or sends {@code FIN} at end of stream. */
  protected void turn(StreamRoom room, StreamConnection client) {
    // The only place client stream bytes are read with credit (readAhead takes one byte without). Out of its
    // data rate, a client waits for the sweep: the bytes stay in the kernel, and TCP pushes back on the client.
    long allowed = allowance(client);
    if (allowed <= 0) {
      client.throttled = true;
      interests(client);
      return;
    }
    ByteRing out = room.mux.out;
    // Alone in the ready queue, a stream may fill the host queue in one read: nobody waits behind it.
    // Either way the queue ends within hostQueueSize + one frame of a slice, as hostQueueLimit allows.
    int slice = room.readyHead != null ? sliceSize :
                Math.min(0xffff, sliceSize + Math.max(0, hostQueueSize - out.size()));
    int max = (int)Math.min(Math.min(slice, client.upCredit), allowed);
    if (max <= 0 || client.clientFin) {
      interests(client);
      return;
    }
    int start = out.size(), read = 0, more = 0;
    try {
      frame(room.mux, DATA, client.conId, 0);
      if (client.readAhead >= 0) {
        out.put(client.readAhead);
        client.readAhead = -1;
        read = 1;
      }
      if (read < max) more = out.read(client.socket, max - read);
    } catch (IOException | RuntimeException | Error e) { // Never half a frame in the host queue.
      out.truncate(start);
      failed(client, e);
      return;
    }
    if (more > 0) read += more;
    if (read > 0) {
      // Bytes kept back by missing credit count when they are taken: a client unblocked gets a whole window.
      client.lastReceived = now;
      out.setShort(start + HEADER_SIZE - LENGTH_SIZE, read);
      client.upCredit -= read;
      spend(client, read);
    } else out.truncate(start);
    if (more < 0) endOfInput(room, client);
    else interests(client);
  }

  /** The client ended its output: {@code FIN} to the host, after the stream's last {@code DATA}. */
  protected void endOfInput(StreamRoom room, StreamConnection client) {
    client.clientFin = true;
    frame(room.mux, FIN, client.conId, 0);
    interests(room.mux);
    if (client.isFinished()) close(client);
    else interests(client);
  }

  /** Reads one byte of a client without credit, for its next turn, so an EOF or a reset behind it is seen. */
  protected void readAhead(StreamConnection client) throws IOException {
    if (client.readAhead >= 0) { // A stale readiness of the same batch: the byte is already kept.
      interests(client);
      return;
    }
    ByteBuffer one = scratch;
    one.clear().limit(1);
    int read = client.socket.read(one);
    if (read < 0) endOfInput(client.room, client);
    else {
      if (read > 0) {
        client.readAhead = one.get(0) & 0xff;
        client.lastReceived = now;
      }
      interests(client);
    }
  }

  /**
   * @return the bytes a stage 3 peer may send now, see {@link ClajConfig#clientRate} and {@link ClajConfig#hostRate}.
   * {@link Long#MAX_VALUE} without limit.
   */
  protected long allowance(StreamConnection con) {
    long perSecond = ClajRelay.rateOf(con.room, con.stage == STAGE_HOST);
    con.limited = perSecond > 0;
    return con.claj.rate.refill(now, perSecond);
  }

  /** Takes what a stage 3 peer sent from its allowance, after {@link #allowance}: out of it, it is throttled. */
  protected void spend(StreamConnection con, int bytes) {
    if (con.limited && bytes > 0 && !con.claj.rate.take(bytes)) con.throttled = true;
  }

  // end region
  // region downstream

  protected void hostRead(StreamConnection host) throws IOException {
    StreamRoom room = host.room;
    long allowed = allowance(host);
    if (allowed <= 0) { // Out of its data rate: read again once the sweep refills it.
      host.throttled = true;
      interests(host);
      return;
    }
    ByteBuffer in = scratch;
    in.clear().limit((int)Math.min(in.capacity(), allowed));
    int read = host.socket.read(in);
    if (read < 0) {
      Log.debug("@: left", host);
      close(host);
      return;
    }
    host.lastReceived = now;
    spend(host, read);
    in.flip();
    while (in.hasRemaining() && !room.isClosed()) {
      if (room.frameType < 0) readHeader(room, in);
      else readPayload(room, in);
    }
    pump(room);
  }

  protected void readHeader(StreamRoom room, ByteBuffer in) throws ProtocolException {
    int type, id, length;
    if (room.headerFill == 0 && in.remaining() >= HEADER_SIZE) {
      type = in.get() & 0xff;
      id = in.getInt();
      length = in.getShort() & 0xffff;
    } else {
      int count = Math.min(HEADER_SIZE - room.headerFill, in.remaining());
      in.get(room.header, room.headerFill, count);
      room.headerFill += count;
      if (room.headerFill < HEADER_SIZE) return;
      room.headerFill = 0;
      ByteBuffer header = ByteBuffer.wrap(room.header);
      type = header.get() & 0xff;
      id = header.getInt();
      length = header.getShort() & 0xffff;
    }

    switch (type) {
      case DATA:
        if (length == 0) throw new ProtocolException("empty DATA");
        if (id == BROADCAST) startBroadcast(room, length);
        else {
          StreamConnection client = stream(room, id);
          if (client != null) {
            if (client.hostFin) throw new ProtocolException("DATA after FIN");
            if (length > client.downCredit) {
              overrun(client);
              client = null; // Its frame is discarded.
            } else {
              client.downCredit -= length;
              client.acknowledged = true;
            }
          }
          room.frameTarget = client;
        }
        break;
      case CLOSE:
      case FIN:
        if (length != 0) throw new ProtocolException("non-empty " + (type == CLOSE ? "CLOSE" : "FIN"));
        StreamConnection client = stream(room, id);
        if (client == null) return;
        if (type == CLOSE) {
          // Detached quietly: the host knows, no CLOSE goes back. The client then lingers.
          server.onConClose(room.host, client.claj.id, DcReason.closed);
        } else hostFin(client);
        return;
      case WINDOW:
        if (length != WINDOW_PAYLOAD_SIZE) throw new ProtocolException("invalid WINDOW length");
        stream(room, id);
        break;
      case CONTROL:
        if (id != ROOM || length < 1 || length > MAX_CONTROL_SIZE) throw new ProtocolException("invalid CONTROL");
        break;
      case DGRAM:
        if (id < 0 || id > room.lastId || length > MAX_DATAGRAM_SIZE) throw new ProtocolException("invalid DGRAM");
        if (length == 0) {
          hostDatagram(room, id, EMPTY);
          return;
        }
        break;
      case PING:
        if (id != ROOM || length != 0) throw new ProtocolException("invalid PING");
        return; // Received, so the host is alive: that's all.
      default:
        throw new ProtocolException("unexpected frame type " + type);
    }
    room.frameType = type;
    room.frameId = id;
    room.frameRemaining = length;
  }

  protected void readPayload(StreamRoom room, ByteBuffer in) throws ProtocolException {
    if (room.frameType == DATA) {
      int count = Math.min(room.frameRemaining, in.remaining());
      if (room.frameId == BROADCAST) copyBroadcast(room, in, count);
      else if (room.frameTarget != null && !room.frameTarget.dead) {
        room.frameTarget.out.put(in, count);
        interests(room.frameTarget);
      } else in.position(in.position() + count); // A late frame of a retired or aborted stream.
      room.frameRemaining -= count;
      if (room.frameRemaining == 0) endData(room);
      return;
    }

    // Other payloads are small: handled in place when whole, gathered in the carry buffer otherwise.
    int length = room.frameRemaining, limit = in.limit(), end = -1;
    ByteBuffer payload;
    if (room.carry != null && room.carry.position() > 0 || in.remaining() < length) {
      if (room.carry == null) room.carry = ByteBuffer.allocate(Math.max(MAX_CONTROL_SIZE, MAX_DATAGRAM_SIZE));
      int count = Math.min(length - room.carry.position(), in.remaining());
      in.limit(in.position() + count);
      room.carry.put(in);
      in.limit(limit);
      if (room.carry.position() < length) return;
      room.carry.flip();
      payload = room.carry;
    } else {
      end = in.position() + length;
      in.limit(end);
      payload = in;
    }

    int type = room.frameType, id = room.frameId;
    room.frameType = -1;
    try {
      if (type == WINDOW) hostWindow(room, id, payload.getInt());
      else if (type == DGRAM) hostDatagram(room, id, payload);
      else control.host(room, payload);
    } finally {
      if (end < 0) room.carry.clear();
      else in.limit(limit).position(end);
    }
  }

  /** @return the open stream {@code id}, {@code null} if retired. @throws ProtocolException if never given. */
  protected StreamConnection stream(StreamRoom room, int id) throws ProtocolException {
    StreamConnection client = room.streams.get(id);
    if (client == null && (id <= 0 || id > room.lastId)) throw new ProtocolException("unknown stream " + id);
    return client;
  }

  protected void hostWindow(StreamRoom room, int id, int amount) throws ProtocolException {
    StreamConnection client = stream(room, id);
    if (client == null) return;
    if (amount <= 0 || amount > WINDOW_SIZE - client.upCredit) throw new ProtocolException("invalid credit " + amount);
    client.acknowledged = true;
    client.upCredit += amount;
    // A read-ahead byte may be all there is: no readable event would come for it.
    if (client.readAhead >= 0) client.room.offer(client);
    interests(client);
  }

  protected void hostFin(StreamConnection client) {
    client.acknowledged = true;
    if (client.hostFin) return;
    client.hostFin = true;
    if (!client.out.isEmpty()) return; // Applied once the queued bytes are written.
    try {
      shutdownOutput(client);
    } catch (IOException e) {
      Log.debug("@: @", client, e);
      close(client);
    }
  }

  /** A broadcast goes to every stream the host acknowledged and didn't end, within the credit of each. */
  protected void startBroadcast(StreamRoom room, int length) {
    StreamConnection[] clients = room.open.items;
    for (int i=room.open.size - 1; i>=0; i--) { // Backwards: a kick removes the client.
      StreamConnection client = clients[i];
      if (client.acknowledged && !client.hostFin && length > client.downCredit) overrun(client);
    }
    for (int i=0; i<room.open.size; i++) {
      StreamConnection client = clients[i];
      if (!client.acknowledged || client.hostFin) continue;
      client.downCredit -= length;
      client.broadcasting = true;
    }
  }

  /** The host sent a client more than its credit: that client can't keep up, it is kicked, the room goes on. */
  protected void overrun(StreamConnection client) {
    Log.debug("@: DATA past its credit, kicked", client);
    client.closeReason = DcReason.error;
    close(client);
  }

  protected void copyBroadcast(StreamRoom room, ByteBuffer in, int count) {
    int position = in.position();
    StreamConnection[] clients = room.open.items;
    for (int i=0; i<room.open.size; i++) {
      StreamConnection client = clients[i];
      if (!client.broadcasting) continue;
      in.position(position);
      client.out.put(in, count);
      interests(client);
    }
    in.position(position + count);
  }

  protected void endData(StreamRoom room) {
    room.frameType = -1;
    room.frameTarget = null;
    if (room.frameId != BROADCAST) return;
    StreamConnection[] clients = room.open.items;
    for (int i=0; i<room.open.size; i++) clients[i].broadcasting = false;
  }

  // end region
  // region datagrams

  protected void receive() {
    udp.receive();
  }

  /** Handles a datagram, from {@code con}, or from {@code from} if unknown: then only a {@code HELLO_UDP} counts. */
  protected void datagram(StreamConnection con, InetSocketAddress from, ByteBuffer in) {
    boolean hello = con == null;
    // The peer is known before anything can fail: an unknown datagram is dropped, never handled.
    if (hello && (con = helloTarget(from, in)) == null) return;
    if (con.closing || con.dead) return;
    // Every datagram that reaches a connection passes here once. Out of its data rate, a peer's are dropped.
    if (con.stage >= STAGE_CLIENT) {
      if (allowance(con) <= 0) return;
      spend(con, in.remaining());
    }
    con.lastReceived = now;
    try {
      if (hello) welcome(con, from);
      else if (con.stage == STAGE_CLIENT) clientDatagram(con, in);
      else if (con.stage == STAGE_HOST && in.remaining() >= DATAGRAM_PREFIX_SIZE)
        hostDatagram(con.room, in.getInt(), in);
      else if (con.stage == STAGE_CONTROL) controlDatagram(con, in);
    } catch (IOException | RuntimeException | Error e) {
      failed(con, e);
    } finally {
      udpRequest = false;
    }
  }

  /**
   * A stage 2 request over UDP, answered over UDP, except the accepts, which stay in the TCP stream. <br>
   * KNOWN RISK, accepted for now: the source address is the only proof of the sender and can be forged,
   * and a small request can get up to 4 KiB sent to it (protocol.md, "Stage 2 over UDP").
   */
  protected void controlDatagram(StreamConnection con, ByteBuffer in) throws IOException {
    // Retransmits of the HELLO_UDP that bound this address, until the WELCOME arrived over TCP.
    if (!in.hasRemaining() || in.get(in.position()) == HELLO_UDP) return;
    if (in.remaining() > MAX_REQUEST_SIZE) throw new ProtocolException("invalid request length " + in.remaining());
    int type = in.get() & 0xff;
    if (type == JOIN_REQUEST || type == ROOM_CREATE) {
      // Before the stage changes, the requests the TCP socket already holds go first: the selector may hand
      // the datagram over before the TCP key, and those bytes would become the first ones of the stream or mux.
      readRequests(con);
      if (con.dead || con.closing || con.stage != STAGE_CONTROL) return; // One of them was a transition.
    }
    // Unread TCP bytes would be parsed in the wrong stage after a join or a creation, as with pipelining.
    boolean pipelined = con.request != null && con.request.position() > 0;
    udpRequest = true;
    try {
      request(con, type, in, pipelined);
    } catch (RuntimeException e) {
      throw new ProtocolException("malformed request " + type + ": " + e);
    }
    if (!con.dead && in.hasRemaining()) throw new ProtocolException("trailing bytes in request " + type);
  }

  /** @return whether {@code in} has the shape of a {@code HELLO_UDP}, which may associate its source address. */
  protected static boolean mayBeHello(ByteBuffer in) {
    return in.remaining() == 1 + Long.BYTES && in.get(in.position()) == HELLO_UDP;
  }

  /** @return the connection a valid {@code HELLO_UDP} associates, {@code null} to drop the datagram. */
  protected StreamConnection helloTarget(InetSocketAddress from, ByteBuffer in) {
    if (!mayBeHello(in)) return null;
    in.get();
    StreamConnection con = tokens.get(in.getLong());
    // The token alone isn't enough, the datagram must come from the same host as the TCP connection.
    if (con == null || con.stage != STAGE_HELLO || !con.address.equals(from.getAddress())) return null;
    return con;
  }

  protected void clientDatagram(StreamConnection client, ByteBuffer payload) {
    StreamRoom room = client.room;
    int length = payload.remaining();
    if (room.isClosed() || length > MAX_DATAGRAM_SIZE) return;
    StreamConnection host = room.mux;
    if (host.udp != null) udp.send(host, client.conId, payload);
    else if (host.out.size() + HEADER_SIZE + length <= hostQueueSize) {
      frame(host, DGRAM, client.conId, length);
      host.out.put(payload, length);
      interests(host);
    }
    // Otherwise the host is behind: the datagram is dropped, never queued.
  }

  /** Sends a host datagram to one stream, or to every acknowledged one. Unknown destinations are dropped. */
  protected void hostDatagram(StreamRoom room, int id, ByteBuffer payload) {
    if (room.isClosed() || payload.remaining() > MAX_DATAGRAM_SIZE) return;
    if (id != BROADCAST) {
      StreamConnection client = room.streams.get(id);
      if (client != null && client.udp != null) udp.send(client, payload);
      return;
    }
    int position = payload.position();
    StreamConnection[] clients = room.open.items;
    for (int i=0; i<room.open.size; i++) {
      StreamConnection client = clients[i];
      if (!client.acknowledged || client.udp == null) continue;
      payload.position(position);
      udp.send(client, payload);
    }
  }

  // end region
  // region interests

  /** Updates the selector interests of a connection from its state. */
  protected void interests(StreamConnection con) {
    if (con.dead) return;
    int ops = 0;
    if (con.out.isEmpty()) con.writeBlocked = false;
    else if (con.writeBlocked) ops = SelectionKey.OP_WRITE;
    else if (!con.dirty) { // Written at the end of the turn, see flushDirty()
      con.dirty = true;
      con.nextDirty = dirtyHead;
      dirtyHead = con;
    }
    if (con.closing) {
      if (con.draining) ops = SelectionKey.OP_READ;
    } else {
      switch (con.stage) {
        case STAGE_HELLO:
        case STAGE_CONTROL:
          if (con.out.isEmpty()) ops |= SelectionKey.OP_READ;
          break;
        case STAGE_CLIENT:
          // With credit: readiness for a turn. Without: one byte of read-ahead, to notice an EOF.
          if (!con.clientFin && !con.ready && !con.throttled && (con.upCredit > 0 || con.readAhead < 0))
            ops |= SelectionKey.OP_READ;
          break;
        case STAGE_HOST:
          if (!con.throttled) ops |= SelectionKey.OP_READ;
          break;
      }
    }
    if (ops == con.ops) return;
    con.ops = ops;
    con.key.interestOps(ops);
  }

  // end region
}
