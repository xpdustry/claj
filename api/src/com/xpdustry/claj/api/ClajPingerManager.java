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

package com.xpdustry.claj.api;

import arc.func.Cons;
import arc.func.Cons2;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Threads;

import com.xpdustry.claj.common.status.RejectReason;
import com.xpdustry.claj.common.status.ServerState;
import com.xpdustry.claj.common.util.Structs;


//FIXME: it's not perfect, keep refreshing servers will fill up pingers, if one is unreachable.
//TODO: check if still happens
public class ClajPingerManager {
  protected final int workers;
  protected final ClajProvider provider;
  protected int created;
  protected final ClajPinger[] pingers;
  protected final Thread[] threads;
  protected final boolean[] reserved;
  protected final Queue<Cons2<ClajPinger, Runnable>> queue = new Queue<>();

  public ClajPingerManager(ClajProvider provider) { this(provider, 1); }
  public ClajPingerManager(ClajProvider provider, int workers) {
    this.workers = workers;
    this.provider = provider;
    pingers = new ClajPinger[workers];
    threads = new Thread[workers];
    reserved = new boolean[workers];
  }

  public String getPingerName(int index) {
    return workers == 1 ? "Claj Pinger" : "Claj Pinger " + (index+1);
  }
  public synchronized boolean hasCreatedPingers() {
    return created > 0;
  }

  public synchronized int createdPingers() {
    return created;
  }

  public int pingers() {
    return workers;
  }

  public ClajProvider provider() {
    return provider;
  }

  public synchronized ClajPinger ensurePingerCreated(int index) {
    if (pingers[index] == null) {
      pingers[index] = provider.newPinger();
      created++;
    }
    return pingers[index];
  }

  public synchronized ClajPinger ensurePingerPingerStarted(int index) {
    ClajPinger pinger = get(index);
    if (!pinger.isRunning()) {
      if (threads[index] != null) threads[index].interrupt(); // in case of
      threads[index] = Threads.daemon(getPingerName(index), pinger);
    }
    return pinger;
  }

  /** @return the first pinger. */
  public ClajPinger get() { return get(0); }
  public ClajPinger get(int index) {
    return ensurePingerCreated(index);
  }

  public synchronized ClajPinger getOrNull(int index) {
    return pingers[index];
  }

  /** Search for a pinger that's not working. */
  public ClajPinger findFree() {
    int index = findFreeI();
    return index == -1 ? null : get(index);
  }

  public synchronized int findFreeI() {
    // First check if empty
    if (created == 0) {
      get();
      return 0;
    }
    // Prioritize existing free pingers
    for(int i=0; i<pingers.length; i++) {
      if (getOrNull(i) != null && !isBusy(i)) return i;
    }
    // Then empty slots
    for(int i=0; i<pingers.length; i++) {
      if (getOrNull(i) == null && !reserved[i]) {
        get(i);
        return i;
      }
    }
    return -1;
  }

  public synchronized boolean isBusy(int index) {
    if (reserved[index]) return true;
    ClajPinger pinger = getOrNull(index);
    return pinger != null && pinger.isWorking();
  }

  /** Dispose all pingers. */
  public synchronized void dispose() {
    cancel();
    for (ClajPinger pinger : pingers) {
      if (pinger == null) continue;
      pinger.stop();
      try { pinger.dispose(); }
      catch (Exception _) {}
    }
    for (Thread t : threads) {
      if (t == null) continue;
      t.interrupt();
    }
  }

  /** Stop all pingers and cancel the queue. */
  public synchronized void stop() {
    cancel();
    for (ClajPinger pinger : pingers) {
      if (pinger != null) pinger.stop();
    }
  }

  /** Cancel the queue and current tasks. */
  public synchronized void cancel() {
    for (ClajPinger pinger : pingers) {
      if (pinger == null) continue;
      pinger.setCancelState(true);
      pinger.close(); // be sure
    }

    // Run rest of the queue
    // We don't care about who will receive the task, this will not block anyway
    //FIXME: not sure of that =/
    while (!queue.isEmpty()) {
      Cons2<ClajPinger, Runnable> task = queue.removeFirst();
      try { task.get(get(), () -> {}); }
      catch (Exception _) {}
    }

    for (ClajPinger pinger : pingers) {
      if (pinger == null) continue;
      pinger.close(); // be sure
      pinger.setCancelState(false);
    }
    for (int i=0; i<pingers.length; i++) reserved[i] = false;
  }

  /** Queue the task if all pingers are busy. */
  protected void submit(Cons2<ClajPinger, Runnable> task) {
    int index = findFreeI();
    if (index != -1) submit(index, task);
    else queue.add(task);
  }

  protected synchronized void submit(int index, Cons2<ClajPinger, Runnable> task) {
    reserved[index] = true;
    Runnable t = () -> {
      ClajPinger pinger = ensurePingerPingerStarted(index);
      task.get(pinger, () -> {
        synchronized (this) {
          //arc.util.Log.info("pinger @ finished. queue empty? @", index, queue.isEmpty());
          if (pinger.canceling || queue.isEmpty()) reserved[index] = false;
          // Keep reserved, execute next
          else submit(index, queue.removeFirst());
        }
      });
    };

    try { provider.postConnect(t); }
    catch (Exception e) { // in case of
      reserved[index] = false;
      throw e;
    }
  }

  public void joinRoom(ClajLink link, Runnable success, Cons<RejectReason> reject, Cons<Exception> failed) {
    if (link == null) return;
    submit((pinger, finished) -> pinger.joinRoom(
      link.host, link.port, link.roomId,
      Structs.and(j -> provider.connectClient(link.host, link.port, success, j), finished),
      Structs.and(reject, finished), Structs.and(failed, finished)
    ));
  }

  public void joinRoom(ClajLink link, short password, Runnable success, Cons<RejectReason> reject,
                       Cons<Exception> failed) {
    if (link == null) return;
    submit((pinger, finished) -> pinger.joinRoom(
      link.host, link.port, link.roomId, password,
      Structs.and(j -> provider.connectClient(link.host, link.port, success, j), finished),
      Structs.and(reject, finished), Structs.and(failed, finished)
    ));
  }

  /** This queues new tasks if a ping is already in progress. */
  public void pingHost(String ip, int port, Cons<ServerState> success, Cons<Exception> failed) {
    submit((pinger, finished) ->
      pinger.pingHost(ip, port, Structs.and(success, finished), Structs.and(failed, finished)));
  }

  public <T> void serverRooms(String ip, int port, Cons<Seq<ClajRoom<T>>> rooms, Cons<Exception> failed) {
    submit((pinger, finished) ->
      pinger.<T>requestRoomList(ip, port, Structs.and(rooms, finished), Structs.and(failed, finished)));
  }

  public <T> void requestRoomInfo(ClajLink link, Cons<ClajRoom<T>> info, Runnable notFound, Cons<Exception> failed) {
    if (link == null) return;
    submit((pinger, finished) -> pinger.<T>requestRoomInfo(
      link.host, link.port, link.roomId,
      Structs.and(info, finished), Structs.and(notFound, finished), Structs.and(failed, finished)
    ));
  }
}
