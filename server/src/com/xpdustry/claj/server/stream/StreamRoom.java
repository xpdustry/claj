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

import java.nio.ByteBuffer;

import arc.net.DcReason;
import arc.struct.IntMap;
import arc.struct.Seq;

import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.server.ClajConnection;
import com.xpdustry.claj.server.ClajRoom;


/**
 * A room of the stream relay: one host and its streams. conIds start at 1 and are never reused. <br>
 * Membership, events and routines are {@link ClajRoom}'s; this adds the mux, through its hooks.
 */
public class StreamRoom extends ClajRoom {
  public final StreamRelay relay;
  /** The host's connection, which carries the mux. */
  public final StreamConnection mux;
  /** Open streams by conId. Closing streams are no longer here. */
  public final IntMap<StreamConnection> streams = new IntMap<>();
  /** Same streams, for iteration without allocation. */
  public final Seq<StreamConnection> open = new Seq<>(false, 8, StreamConnection.class);
  /** Last given conId. */
  public int lastId;

  // region upstream scheduling

  /** Clients readable with credit, served one slice each in turn. */
  protected StreamConnection readyHead, readyTail;
  /** Whether some streams have credit to return to the host, once its queue has room. */
  protected boolean creditPending;

  // end region
  // region host mux parser

  /** Partial frame header, when a read ends in the middle of one. */
  protected final byte[] header = new byte[StreamProtocol.HEADER_SIZE];
  protected int headerFill;
  /** Frame whose payload is being read, {@code -1} if none. */
  protected int frameType = -1, frameId, frameRemaining;
  /** Destination of the current {@code DATA} payload, {@code null} to discard it. */
  protected StreamConnection frameTarget;
  /** Payload of a non {@code DATA} frame split across reads, allocated the first time it happens. */
  protected ByteBuffer carry;

  // end region

  public StreamRoom(StreamRelay relay, long id, ClajConnection host, ClajType type) {
    super(id, host, type);
    this.relay = relay;
    this.mux = (StreamConnection)host.connection;
  }

  // region membership

  /** The host gets its mux, a client its stream and an {@code OPEN}, see {@link #notifyJoined}. */
  @Override
  protected void setRoom(ClajConnection con) {
    super.setRoom(con);
    StreamConnection stream = (StreamConnection)con.connection;
    if (isHost(con)) relay.startHost(stream, this);
    else relay.startClient(stream, this);
  }

  /** Detaches a stream from the room. Its id stays retired. */
  @Override
  protected void removeRoom(ClajConnection con) {
    super.removeRoom(con);
    StreamConnection client = (StreamConnection)con.connection;
    if (client.room != this || client == mux || streams.remove(client.conId) == null) return;
    open.remove(client, true);
    if (frameTarget == client) frameTarget = null; // The rest of its DATA frame is discarded.
    if (!client.ready) return;
    StreamConnection previous = null;
    for (StreamConnection c = readyHead; c != null; previous = c, c = c.nextReady) {
      if (c != client) continue;
      if (previous == null) readyHead = c.nextReady;
      else previous.nextReady = c.nextReady;
      if (readyTail == c) readyTail = previous;
      break;
    }
    client.ready = false;
    client.nextReady = null;
  }

  @Override
  protected void notifyJoined(ClajConnection connection) {
    relay.open(this, (StreamConnection)connection.connection);
  }

  @Override
  protected void notifyLeft(ClajConnection connection, DcReason reason) {
    relay.left(this, (StreamConnection)connection.connection, reason);
  }

  /** The stream wire has no broadcast {@code CLOSE}: when notified, the host gets one per stream retired. */
  @Override
  public void disconnectAllClients(DcReason reason, boolean notify) {
    if (!notify || clients.isEmpty()) {
      super.disconnectAllClients(reason, notify);
      return;
    }
    Seq<ClajConnection> retired = clients.copy();
    try { super.disconnectAllClients(reason, false); }
    finally { retired.each(c -> notifyLeft(c, reason)); }
  }

  /** A host far behind its queue can't be told about a new stream either, and conIds may run out. */
  @Override
  public boolean isFull() {
    return super.isFull() || lastId == Integer.MAX_VALUE || mux.out.size() > relay.hostQueueSize;
  }

  /** The stream wire has no state requests: {@code requestState} is ignored. */
  @Override
  public void setConfiguration(boolean isPublic, boolean isProtected, short password, boolean requestState,
                               int maxClients) {
    super.setConfiguration(isPublic, isProtected, password, false, maxClients);
  }

  // end region
  // region ready queue

  /** Appends a client to the ready queue, if not already in it. */
  public void offer(StreamConnection client) {
    if (client.ready) return;
    client.ready = true;
    if (readyTail == null) readyHead = client;
    else readyTail.nextReady = client;
    readyTail = client;
  }

  /** @return the next ready client, or {@code null}. */
  public StreamConnection poll() {
    StreamConnection client = readyHead;
    if (client == null) return null;
    readyHead = client.nextReady;
    if (readyHead == null) readyTail = null;
    client.nextReady = null;
    client.ready = false;
    return client;
  }

  // end region
}
