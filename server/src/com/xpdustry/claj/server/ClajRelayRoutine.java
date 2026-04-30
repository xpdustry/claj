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

package com.xpdustry.claj.server;

import arc.func.Cons;
import arc.net.NetListener;
import arc.struct.LongMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Ratekeeper;
import arc.util.Time;
import arc.util.Timer;

import com.xpdustry.claj.common.net.stream.PreparedStream;
import com.xpdustry.claj.common.net.stream.StreamSender;
import com.xpdustry.claj.common.packets.RoomListPacket;
import com.xpdustry.claj.common.status.ClajType;


//TODO: close empty rooms after 1 hour.
//TODO: remove references to relay
/** Class holding CLaJ routine such as cleaning AddressRater or closing afk rooms. */
public class ClajRelayRoutine implements NetListener {
  protected final ClajRelay relay;
  
  /** List of join, info and list request rates by ip. */
  protected final ObjectMap<String, AddressRater> rates = new ObjectMap<>(32);
  /** List of client who requested the state of a room that was outdated.*/
  protected final LongMap<Seq<ClajConnection>> pendingInfoRequests = new LongMap<>(16);
  /** Use cleaner task instead of storing the {@link #pendingInfoRequests} invert, to avoid having to many caches. */
  protected final LongMap<Timer.Task> pendingInfoTasks = new LongMap<>(16);
  /** Cache for room list requests. */
  protected final ObjectMap<ClajType, CachedRoomList> listCache = new ObjectMap<>(8);

  public ClajRelayRoutine(ClajRelay relay) {
    this.relay = relay;
    relay.addListener(this);
  }
  
  public void clearCaches() {
    pendingInfoRequests.forEach(e -> e.value.each(c -> relay.rejectRoomInfo(c, relay.getRoom(e.key), false)));
    pendingInfoRequests.clear();
    pendingInfoTasks.eachValue(Timer.Task::cancel);
    pendingInfoTasks.clear();
    listCache.each((_, c) -> c.send());
    listCache.clear();
  }
  
  public void clearRoomCache(ClajRoom room, boolean removeFromList, Cons<ClajConnection> infoRejection) {
    Seq<ClajConnection> cons = pendingInfoRequests.remove(room.id);
    if (cons != null) cons.each(infoRejection);
    Timer.Task task = pendingInfoTasks.remove(room.id);
    if (task != null) task.cancel();

    if (room.type == null) return;
    CachedRoomList c = listCache.get(room.type);
    if (c == null) return;
    c.remove(room.id);
    if (!removeFromList) return;
    c.send(); // in case of pending requests
    listCache.remove(room.type);
  }
  
  public boolean requestRoomState(ClajConnection con, ClajRoom room, Runnable rejected, Runnable task) {
    Seq<ClajConnection> cons = pendingInfoRequests.get(room.id);
    if (cons == null) pendingInfoRequests.put(room.id, cons = new Seq<>(false, 4));
    int limit = ClajConfig.infoRequestLimit.get();
    if (limit > 0 && cons.size >= limit) {
      rejected.run();
      return false;
    }
    
    cons.add(con);

    if (!room.requestState()) return false;
    Timer.Task old = pendingInfoTasks.put(room.id, Timer.schedule(() -> {
      pendingInfoTasks.remove(room.id);
      task.run();
    }, ClajConfig.stateTimeout.get() / 1000));
    if (old != null) old.cancel(); // In case of
    return true;
  }
  
  public Seq<ClajConnection> getPendingRoomRequests(ClajRoom room) {
    return pendingInfoRequests.get(room.id);
  }
    
  public Seq<ClajConnection> getPendingRoomRequestsForSend(ClajRoom room) {
    return pendingInfoRequests.remove(room.id);
  }
  
  public int requestRoomList(ClajConnection con, ClajType type, LongMap<ClajRoom> rooms, Cons<Boolean> rejected) {
    if (rooms == null) {
      rejected.get(false);
      return 4;
    }
    CachedRoomList cache = getListCache(type, rooms);
    int limit = ClajConfig.listRequestLimit.get();
    if (limit > 0 && cache.pending.size >= limit) {
      rejected.get(true);
      return 3;
    } else if (cache.updating()) {
      cache.pending.add(con);
      return 2;
    } else if (!cache.isOutdated()) {
      con.sendStream(cache.packet);
      return 1;
    } else {
      cache.pending.add(con);
      cache.refresh(rooms);
      return 0;
    }
  }
  
  
  public void updateRoomCache(ClajRoom room, boolean stateChanged) {
    CachedRoomList cache = listCache.get(room.type);
    if (cache != null) cache.set(room, stateChanged);
  }
  
  public boolean sendRoomList(ClajType type, boolean force) {
    CachedRoomList cache = listCache.getNull(type);
    if (cache == null || !force && cache.updating()) return false;
    cache.send();
    return true;
  }

  public boolean refreshRoomList(ClajType type, boolean force, LongMap<ClajRoom> rooms) {
    if (rooms == null) return false;
    CachedRoomList cache = getListCache(type, rooms);
    if (!force && cache.updating()) return false;
    cache.refresh(rooms);
    return true;
  }
  
  public void refreshRoomList(ClajType type, LongMap<ClajRoom> rooms) {
    getListCache(type, rooms).refresh(rooms);
  }
    
  protected CachedRoomList getListCache(ClajType type, LongMap<ClajRoom> fallbackRooms) {
    return listCache.get(type, () -> new CachedRoomList(type, fallbackRooms));
  }

  public AddressRater getAddressRate(ClajConnection con) {
     return rates.get(con.address, () -> new AddressRater(con.address));
  }

  
  
  
  protected static class CachedRoomList {
    public final ClajType type;
    public final RoomListPacket packet;
    public long lastUpdate;
    public final Seq<ClajConnection> pending = new Seq<>(false, 16);
    public final ObjectSet<Long> requesting = new ObjectSet<>();
    public Timer.Task refreshTask;
    private PreparedStream cachedStream;
    private boolean streamDirty = true;

    public CachedRoomList(ClajType type, LongMap<ClajRoom> rooms) {
      this.type = type;
      packet = new RoomListPacket();
      rooms.eachValue(r -> {
        if (!r.shouldRequestState()) return;
        packet.states.put(r.id, r.rawState);
        if (r.isProtected) packet.protectedRooms.add(r.id);
      });
    }

    public void remove(long room) {
      packet.states.remove(room);
      packet.protectedRooms.remove(room);
      requesting.remove(room);
      streamDirty = true;
    }

    public void set(ClajRoom room, boolean stateChanged) {
      if (!room.isPublic) {
        remove(room.id);
        return;
      }
      packet.states.put(room.id, room.rawState);
      if (room.isProtected) packet.protectedRooms.add(room.id);
      else packet.protectedRooms.remove(room.id);
      if (stateChanged) requesting.remove(room.id);
      streamDirty = true;
    }

    public void refresh(LongMap<ClajRoom> rooms) { refresh(rooms, this::send); }
    public void refresh(LongMap<ClajRoom> rooms, Runnable done) {
      lastUpdate = Time.millis();
      rooms.eachValue(r -> {
        if (r.shouldRequestState() && r.isStateOutdated(lastUpdate) && r.requestState(lastUpdate))
          requesting.add(r.id);
      });

      if (refreshTask != null) refreshTask.cancel(); // In case of
      if (!updating()) {
        refreshTask = null;
        done.run();
        return;
      }
      refreshTask = Timer.schedule(() -> {
        refreshTask = null;
        done.run();
      }, ClajConfig.listTimeout.get() / 1000);
    }

    public void send() {
      if (refreshTask != null) {
        refreshTask.cancel();
        refreshTask = null;
      }
      requesting.clear();

      if (pending.isEmpty()) return;
      Log.debug("Sending room list of type @ to @ pending request" + (pending.size > 1 ? "s..." : "..."),
                type, pending.size);
      if (streamDirty || cachedStream == null) {
        cachedStream = StreamSender.prepare(packet);
        streamDirty = false;
      }
      pending.each(c -> c.sendStream(cachedStream));
      pending.clear();
    }

    public boolean isOutdated() {
      int life = ClajConfig.listLifetime.get();
      return life > 0 && Time.timeSinceMillis(lastUpdate) >= life;
    }

    public boolean updating() {
      return requesting.size > 0;
    }
  }

  
  //TODO: clean cache
  public static class AddressRater {
    public final String address;
    public final Ratekeeper joinRate = new Ratekeeper();
    public final Ratekeeper infoRate = new Ratekeeper();
    public final Ratekeeper listRate = new Ratekeeper();

    public AddressRater(String address) {
      this.address = address;
    }

    public boolean allowJoin() {
      int limit = ClajConfig.joinLimit.get() * 2; // because joining makes 2 requests
      return limit <= 0 || joinRate.allow(60000L, limit);
    }

    public boolean allowInfo() {
      int limit = ClajConfig.infoLimit.get();
      return limit <= 0 || infoRate.allow(60000L, limit);
    }

    public boolean allowList() {
      int limit = ClajConfig.listLimit.get();
      return limit <= 0 || listRate.allow(60000L, limit);
    }
  }
}
