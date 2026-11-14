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

import java.nio.ByteBuffer;

import arc.struct.Seq;

import com.xpdustry.claj.bench.Loop.Flow;


/** A room with its host and clients, all owned by one loop. Each client ends up joined or failed, never both. */
final class Room {
  final Loop loop;
  final Host host;
  /** Whether the broadcast flow uses UDP. Its clients will use UDP too. */
  final boolean udp;
  final Seq<Client> clients = new Seq<>(Client.class);

  long id;
  boolean linked, ready, broken;
  /** Clients that joined (even if gone since), that failed to join, and that are alive. */
  int joined, failed, alive;

  Room(Loop loop, boolean udp) {
    this.loop = loop;
    this.udp = udp;
    host = new Host();
  }

  /** From the loop thread. */
  void addClients(int n) {
    if (n <= 0) return;
    Bench b = loop.bench;
    if (broken) {
      b.failedClients.addAndGet(n);
      return;
    }
    for (int i=0; i<n; i++) {
      Client c = new Client(udp || loop.rand.chance(b.sc.udp()));
      clients.add(c);
      for (byte kind : b.sc.pattern()) {
        if (kind != Flow.BROADCAST) loop.addFlow(kind, this, c, c.keepUdp);
      }
      if (linked) loop.connectQueue.add(c);
    }
  }

  private void linked(long id) {
    this.id = id;
    linked = true;
    clients.each(loop.connectQueue::add);
    checkReady();
  }

  private void clientJoined(int conId) {
    Client c = clients.find(x -> x.conId == conId && !x.joined);
    if (c == null) {
      loop.stats.errors++;
      return;
    }
    c.joined = true;
    joined++;
    alive++;
    loop.bench.joinedClients.incrementAndGet();
    checkReady();
  }

  private void clientFailed() {
    failed++;
    loop.bench.failedClients.incrementAndGet();
    checkReady();
  }

  private void checkReady() {
    if (ready || !linked || joined + failed < clients.size) return;
    if (clients.size > 0 && joined == 0) fail();
    else {
      ready = true;
      loop.bench.readyRooms.incrementAndGet();
    }
  }

  void fail() {
    if (broken) return;
    broken = true;
    int lost = clients.size - joined - failed; // not accounted yet
    if (lost > 0) {
      failed += lost;
      loop.bench.failedClients.addAndGet(lost);
    }
    if (!ready) loop.bench.failedRooms.incrementAndGet();
    host.close("room failed");
    clients.each(c -> c.close("room failed"));
  }

  /** From the loop thread, periodically. */
  void housekeeping() {
    if (broken) return;
    host.housekeeping();
    clients.each(Peer::housekeeping);
  }

  /** A setup failure, or a disconnect if the room was ready. */
  private void lost(String who, String reason, boolean disconnect) {
    if (disconnect) {
      loop.stats.disconnects++;
      loop.bench.disconnect(who + ": " + reason);
    } else loop.bench.failure(who + ": " + reason);
  }

  // region peers

  final class Host extends Peer {
    Host() {
      super(Room.this.loop, Room.this.loop.bench.hostBuffer, false);
    }

    @Override
    void onRegistered() {
      if (!beginFrame(64)) {
        close("unable to write room creation");
        return;
      }
      loop.bench.proto.writeCreate(out);
      endFrame();
    }

    @Override
    boolean alive() {
      return state == REGISTERED && ready && !broken;
    }

    @Override
    void housekeeping() {
      super.housekeeping();
      if (!linked && state == REGISTERED && loop.now - stateTime > STEP_TIMEOUT_NS) close("room creation timeout");
    }

    @Override
    void onFrame(ByteBuffer b, int pos, int len) {
      Stats s = loop.stats;
      if (b.get(pos) != Proto.CLAJ || len < 2) {
        s.errors++;
        return;
      }
      switch (loop.bench.proto.kindOf(b, pos)) {
        case Proto.K_PAYLOAD -> {
          if (len < Proto.WRAP_HEADER + Loop.PAYLOAD_HEADER) {
            s.errors++;
            return;
          }
          int conId = b.getInt(pos + 2), p = pos + Proto.WRAP_HEADER, plen = len - Proto.WRAP_HEADER;
          if (loop.received(b, p, plen) == Loop.ECHO_REQUEST) loop.echo(this, conId, b, p, plen);
        }
        case Proto.K_CON_JOIN ->
          clientJoined(b.getInt(pos + 2));
        case Proto.K_LINK -> {
          if (!linked) linked(b.getLong(pos + 2));
        }
        case Proto.K_IDLE -> {
          s.control++;
          s.idle++;
        }
        case Proto.K_ROOM_CLOSED ->
          close("room closed by server");
        default ->
          s.control++; // client closures are seen by the clients themselves
      }
    }

    @Override
    void onClosed(String reason) {
      if (broken) return;
      lost("host", reason, ready);
      fail();
    }
  }

  final class Client extends Peer {
    boolean joined;

    Client(boolean udp) {
      super(Room.this.loop, Room.this.loop.bench.clientBuffer, udp);
    }

    @Override
    void onRegistered() {
      if (!beginFrame(64)) {
        close("unable to write join request");
        return;
      }
      loop.bench.proto.writeJoin(out, id);
      endFrame();
    }

    @Override
    boolean alive() {
      return joined && state == REGISTERED && !broken;
    }

    @Override
    void housekeeping() {
      super.housekeeping();
      if (!joined && state == REGISTERED && loop.now - stateTime > STEP_TIMEOUT_NS)
        close("join timeout");
    }

    @Override
    void onFrame(ByteBuffer b, int pos, int len) {
      if (b.get(pos) != Proto.CLAJ) {
        if (loop.received(b, pos, len) < 0) loop.stats.errors++;
      } else if (len >= 2 && loop.bench.proto.kindOf(b, pos) == Proto.K_JOIN_DENIED)
        close("join denied");
      else loop.stats.control++;
    }

    @Override
    void onDatagram(ByteBuffer b, int pos, int len) {
      if (b.get(pos) != Proto.FRAMEWORK && loop.received(b, pos, len) < 0)
        loop.stats.errors++;
    }

    @Override
    void onClosed(String reason) {
      if (broken) return; // accounted by the room
      if (!joined) {
        lost("client", reason, false);
        clientFailed();
      } else {
        alive--;
        if (ready) lost("client", reason, true);
      }
    }
  }

  // end region
}
