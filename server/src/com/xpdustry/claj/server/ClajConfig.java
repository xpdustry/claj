/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2025-2026  Xpdustry
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

import arc.Files;
import arc.files.Fi;
import arc.func.Boolf;
import arc.func.Boolf2;
import arc.func.Cons;
import arc.func.Func;
import arc.func.Prov;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Log.LogLevel;
import arc.util.serialization.*;

import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.common.util.Structs;
import com.xpdustry.claj.server.util.Autosaver;
import com.xpdustry.claj.server.util.JsonSettings;


public class ClajConfig {
  public static final Seq<Field<?>> all = new Seq<>();
  public static String fileName = "config.json";
  protected static JsonSettings settings;

  @SuppressWarnings("rawtypes")
  public static void init() {
    settings = new JsonSettings(new Fi(fileName, Files.FileType.local), true, true, true, false);

    settings.addSerializer(ClajType.class, new Json.Serializer<>() {
      public void write(Json json, ClajType object, Class knownType) { json.writeValue(object.type());}
      public ClajType read(Json json, JsonValue jsonData, Class type) {
        ClajType t = ClajType.of(jsonData.asString());
        if (t == null) throw new SerializationException("Invalid CLaJ Type");
        return t;
      }
    });
    // Add more boolean cases
    settings.addSerializer(Boolean.class, new Json.Serializer<>() {
      public void write(Json json, Boolean object, Class knownType) { json.writeValue(object); }
      public Boolean read(Json json, JsonValue jsonData, Class type) {
        Boolean v = Strings.parsePrimitive(Boolean.class, jsonData.asString());
        if (v == null) throw new SerializationException("Invalid boolean value");
        return v;
      }
    });
  }

  public static void load() {
    try {
      if (settings == null) init();
      settings.load();
      all.each(Field::load);
      settings.backup();
    } catch (Exception e) {
      String file = settings == null ? fileName : settings.file().path();
      throw new RuntimeException("Failed to load configuration of file '" + file + "'", e);
    }
  }

  public static void reload() {
    try {
      if (settings == null) return;
      settings.load();
      all.each(Field::reload);
    } catch (Exception e) {
      String file = settings == null ? fileName : settings.file().path();
      throw new RuntimeException("Failed to reload configuration of file '" + file + "'", e);
    }
  }

  /** Force save all fields. Do nothing if config was not initialized. */
  public static void save() {
    try {
      if (settings == null) return;
      all.each(Field::save);
      settings.save();
    } catch (Exception e) {
      String file = settings == null ? fileName : settings.file().path();
      throw new RuntimeException("Failed to sve configuration in file '" + file + "'", e);
    }
  }

  /** Case is ignored. */
  @SuppressWarnings("unchecked")
  public static <T> Field<T> get(String key) {
    return (Field<T>)all.find(f -> f.key.equalsIgnoreCase(key));
  }


  @SuppressWarnings("unchecked")
  public static class Field<T> implements Autosaver.Saveable {
    public final String key, desc;
    protected final T defaultValue;
    public final Class<T> type;
    protected Cons<T> changed;
    /** Values the field accepts, {@code null} for any of its type, and their description. See {@link #check}. */
    protected Boolf<T> validator;
    protected String rule;
    protected T value;
    protected boolean loaded, modified;

    public Field(String key, String desc, T def) { this(key, desc, def, null); }
    public Field(String key, String desc, T def, Cons<T> changed) {
      if (key == null || (key = key.trim()).isEmpty()) throw new IllegalArgumentException("'key' is null");
      if (ClajConfig.get(key) != null) throw new IllegalArgumentException("Field '" + key + "' already exists");
      if (key.equals("reload")) throw new IllegalArgumentException("Reserved key name '" + key + "'");
      if (def == null) throw new IllegalArgumentException("Default value cannot be null");

      this.key = key;
      this.desc = desc == null ? "<unknown>" : desc;
      this.defaultValue = def;
      this.type = (Class<T>)def.getClass();
      this.changed = changed == null ? _ -> {} : changed;

      all.add(this);
      Autosaver.add(this, Autosaver.Priority.normal);
    }

    /** @return "field '" + {@link key} + "'". */
    public String name() {
      return "field '" + key + "'";
    }

    public boolean loaded() {
      return loaded;
    }

    /**
     * Restricts the values: {@link #set} and {@link #decode} refuse the others, {@link #reload} keeps the
     * current value instead of one of them.
     * @param rule describes the valid values, for the errors.
     * @return this field.
     */
    public Field<T> check(String rule, Boolf<T> validator) {
      this.rule = rule;
      this.validator = validator;
      return this;
    }

    /** {@link #check} of a number: from {@code min} to {@code max}, which can be the type's maximum. */
    public Field<T> range(long min, long max) {
      return check(max >= Integer.MAX_VALUE ? "must be at least " + min : "must be from " + min + " to " + max,
                   v -> ((Number)v).longValue() >= min && ((Number)v).longValue() <= max);
    }

    /** {@link #check} of a number: above {@code 0}, up to a day for seconds. */
    public Field<T> positive() {
      return check("must be above 0 and at most 86400", v -> ((Number)v).doubleValue() > 0 &&
                                                            ((Number)v).doubleValue() <= 86400);
    }

    /** @return whether the value passes {@link #check}. */
    public boolean valid(T value) {
      return validator == null || validator.get(value);
    }

    /** @throws IllegalArgumentException if the value doesn't pass {@link #check}. */
    protected T checkValid(T value) {
      if (!valid(value)) throw new IllegalArgumentException("Invalid value " + value + " for " + name() + ", " + rule);
      return value;
    }

    public boolean modified() {
      return modified;
    }

    protected <V> V checkValue(V value, Class<V> type, boolean forParsing) {
      if (value == null || value.getClass() != type) {
        String msg;
        if (forParsing) {
          if (value == null) msg = "Decoded value cannot be null";
          else msg = "Decoded value must be a " + type.getSimpleName();
        } else {
          String valueType = value == null ? null : value.getClass().getName();
          msg = "Invalid value type for " + name() + "; " + valueType + "!=" + type.getName();
        }
        throw new IllegalArgumentException(msg);
      }
      return value;
    }

    protected T loadValue() {
      return settings.getOrPut(key, type, getDefault());
    }

    public void reload() {
      if (settings == null || !settings.loaded())
        throw new IllegalStateException("Settings are not initialized");
      T read = checkValue(loadValue(), type, false);
      if (valid(read)) value = read;
      else {
        if (!loaded) value = getDefault();
        Log.err("Invalid value @ for @, @: kept @.", read, name(), rule, value);
      }
      loaded = true;
      modified = false;
    }

    public void load() {
      if (loaded) return;
      reload();
    }

    protected void saveValue() {
      settings.put(key, value);
    }

    public void save() {
      if (!loaded) return;
      if (settings == null || !settings.loaded())
        throw new IllegalStateException("Settings are not initialized");
      saveValue();
      modified = false;
    }

    public T get() {
      load();
      return value;
    }

    /** @return {@link #defaultValue} if not {@link #loaded}, else {@link #value}. */
    public T getOrDefault() {
      return loaded ? value : getDefault();
    }

    public void set(T value) {
      this.value = checkValid(checkValue(value, type, false));
      notifyChange();
    }

    protected void notifyChange() {
      modified = true;
      changed.get(value);
    }

    public T getDefault() {
      return defaultValue;
    }

    public void setDefault() {
      set(getDefault());
    }

    public boolean isDefault() {
      return Structs.eq(get(), getDefault());
    }

    /** Adds changed callback. */
    public void changed(Cons<T> changed) {
      // Too lazy for a list, just chain that
      Cons<T> old = this.changed;
      this.changed = v -> {
        old.get(v);
        changed.get(v);
      };
    }

    @Override
    public String toString() {
      return String.valueOf(get());
    }

    protected T decodeValue(String value) {
      return settings.getJson().fromJson(type, value);
    }

    /**
     * Tries to decode the value from a {@link String} by parsing and decoding as Json.
     * @throws IllegalArgumentException if decoding failed, or decoded type is not the same.
     */
    public T decode(String value) throws IllegalArgumentException {
      T decoded = parse(value, type, this::decodeValue);
      if (!valid(decoded)) throw new IllegalArgumentException(rule);
      return decoded;
    }

    protected <V> V parse(String value, Class<V> type, Func<String, V> jsonDecoder)
    throws IllegalArgumentException {
      // Fast path
      V decoded = Strings.parsePrimitive(type, value);
      if (decoded != null) return decoded;
      if (type == Boolean.class) throw new IllegalArgumentException("Invalid boolean value");
      // Slow path via json decoding
      try { decoded = jsonDecoder.get(value); }
      catch (IllegalArgumentException e) { throw e; } // Useless to wrap
      catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
      return checkValue(decoded, type, true);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static class SetField<T> extends Field<ObjectSet<T>> {
    protected static final ObjectSet EMPTY = new ObjectSet<>(0);

    public final Class<T> elementType;
    protected final Prov<ObjectSet<T>> defaultMaker;

    public SetField(String key, String desc, Class<T> elementType) {
      this(key, desc, elementType, ObjectSet::new, null);
    }
    public SetField(String key, String desc, Class<T> elementType, Cons<ObjectSet<T>> changed) {
      this(key, desc, elementType, ObjectSet::new, changed);
    }
    public SetField(String key, String desc, Class<T> elementType, Prov<ObjectSet<T>> def) {
      this(key, desc, elementType, def, null);
    }

    public SetField(String key, String desc, Class<T> elementType, Prov<ObjectSet<T>> def, Cons<ObjectSet<T>> changed) {
      super(key, desc, EMPTY, changed);
      this.elementType = elementType;
      defaultMaker = def;
    }

    @Override
    protected <V> V checkValue(V value, Class<V> type, boolean forParsing) {
      V v = super.checkValue(value, type, forParsing);
      if (type == this.type) ((ObjectSet<T>)v).each(e -> checkValue(e, elementType, forParsing));
      return v;
    }

    protected ObjectSet<T> loadValue() { return settings.getOrPut(key, type, elementType, defaultMaker); }
    protected void saveValue() { settings.put(key, elementType, value.toSeq()); }
    public ObjectSet<T> getDefault() { return defaultMaker.get(); }
    protected ObjectSet<T> decodeValue(String value) { return settings.getJson().fromJson(type, elementType, value); }
    public T decodeElement(String value) throws IllegalArgumentException {
      return parse(value, elementType, v -> settings.getJson().fromJson(elementType, v));
    }

    public boolean add(T value) { return action(value, ObjectSet::add, true); }
    public boolean remove(T value) { return action(value, ObjectSet::remove, true); }
    public boolean contains(T value) { return action(value, ObjectSet::contains, false); }
    public void clear() { get().clear(); notifyChange(); }

    protected boolean action(T value, Boolf2<ObjectSet<T>, T> method, boolean notify) {
      boolean done = method.get(get(), checkValue(value, elementType, false));
      if (done && notify) notifyChange();
      return done;
    }

    @Override
    public String toString() {
      return get().isEmpty() ? "[]" :
             "[\n  " + Strings.tableify(get().toSeq().map(o -> "\"" + o + "\","), 128).toString("\n  ") + "\n]";
    }
  }


  private static Seq<String> fieldDescs = Seq.with(
      "Toggle debug log level",
      Strings.format("""
      Write logs of defined level, and above, to files.
      Possible values, in order: &lb@&lw
      Set to &lbdebug&lw to write everything, or &lbnone&lw to write nothing.
      """, Strings.toSentence(Structs.iterable(LogLevel.values()), LogLevel::name, ", ", " or ")),
      "Maximum number of connections (not clients) allowed on this server. Set to &lb0&lw to disable.",
      """
      Maximum number of connections allowed per ip address.
      Keep it high enough for clients sharing the same address. Set to &lb0&lw to disable.
      """.trim(),
      """
      The time to wait for a new connection, to complete its registration, before closing it. (in seconds)
      A connection can wait up to twice this value.
      Prevents half-opened connections from piling up. Set to &lb0&lw to disable.
      """.trim(),
      "Maximum number of rooms that can be created on this server. Set to &lb0&lw to disable.",
      """
      Maximum number of rooms allowed per ip address. Also used as a rate-limit per minute.
      Set to &lb0&lw to disable.
      """.trim(),
      "Maximum number of clients allowed per room. Set to &lb0&lw to disable.",
      """
      Data rate a client may send. (in MB/s, 1 MB = 1,000,000 bytes, with a burst of 3 seconds)
      The stream relay slows the client down, a 2.x client is kicked. A 2.x packet counts as 1 KB at least.
      Set to &lb0&lw to disable.
      """.trim(),
      """
      Data rate a room host may send, for each client of its room and itself. (in MB/s, like client-rate)
      The stream relay slows the host down, a 2.x room is closed. Set to &lb0&lw to disable.
      """.trim(),
      """
      Limit of room join requests per minute per ip address.
      The server will act as the room was not found, which can prevent from room searching.
      Set to &lb0&lw to disable.
      """.trim(),
      """
      Limit of room info requests per minute per ip address.
      The server will act as the room is not found. Set to &lb0&lw to disable.
      """.trim(),
      """
      Limit of room list requests per minute per ip address.
      The server will return an empty list. Set to &lb0&lw to disable.
      """.trim(),
      """
      Limit of new states that can be received from room host per minute.
      The server will ignore them. Set to &lb0&lw to disable.
      """.trim(),
      """
      Limit of clients that can wait for the new state of a room.
      A low value can limit DDoS attacks, but may annoy users. Set to &lb0&lw to disable.
      """.trim(),
      """
      Limit of clients that can wait for the new room list of a type.
      A low value can limit DDoS attacks, but may annoy users. Set to &lb0&lw to disable.
      """.trim(),
      """
      Whether to accept or not clients who attempt to join a room without specifying their CLaJ implementation.
      Setting this to false will break compatibility with older CLaJ versions.
      """.trim(),
      """
      Warn a client that trying to create a room, that it's CLaJ version is deprecated.
      This do not warn ones who trying to join a room.
      """.trim(),
      "Warn all rooms when the server is closing.",
      "The time to wait before exiting the server when closing it. (in seconds)",
      "The time after which a new room status will be requested, if necessary. (in seconds)",
      "The time to wait for the room's new status. (in seconds)",
      "The time after which a new status will be requested to all rooms, if necessary. (in seconds)",
      """
      Listing public rooms can be very long when requesting states,
      this defines the time before the list is send as is, even some state was not received.
      """.trim(),
      """
      The time after which the IP address rater will be removed. (in seconds)
      Because join, info, and list limitations uses IP addresses, to prevent peoples from resetting their
      rate-limits just by reconnecting, it's necessary to clean them after a time not seen the IP address,
      to avoid a potential OOM. Set to &lb0&lw to disable.
      """.trim(),
      """
      The time after which the room will be closed for AFK. (in minutes)
      Here, AFK means that no CLaJ clients has joined the room for a long time.
      Even if, in reality, there are connected clients, but with another way than CLaJ.
      Set to &lb0&lw to disable.
      """.trim(),
      """
      Whether to divide work to two threads. (needs a restart to take effect)
      One handling connections and sockets reads/writes.
      And the other handling packet decoding, business logic, and serialization.
      This increases speed and processing capacity, at the cost of double or triple of the RAM
      and slightly higher CPU usage. This can be helpful for heavy load servers.
      &fiWARNING: this is currently experimental, setting this to false can break things.&fr
      """.trim(),
      "IP Address Blacklist.",
      "CLaJ Type Blacklist.",
      "Address to bind the server ports to, every interface if empty. (needs a restart to take effect)",
      """
      Port of the stream relay, TCP and UDP, next to the 2.x one. (needs a restart to take effect)
      It uses max-connections, connection-limit, max-rooms, client-limit and the rate limits too.
      Set to &lb0&lw to disable.
      """.trim(),
      """
      Stream relay: bytes it may commit to queues. Set to &lb0&lw for half the maximum heap.
      (needs a restart to take effect)
      """.trim(),
      "Stream relay: deadline to create or join a room, since the connection. (in seconds)",
      "Stream relay: time given to a closing connection to flush its queued bytes. (in seconds)",
      """
      Stream relay: closes a client or host from which nothing was received for this long. (in seconds)
      Set to &lb0&lw to disable.
      """.trim(),
      """
      Stream relay: TCP keepalive idle time, drawn up to 50% above per socket, then 3 probes at half of it.
      (in seconds) Set to &lb0&lw to disable keepalive.
      """.trim(),
      "Stream relay: requests before a connection is closed, refused or not.",
      """
      Stream relay: largest data frame sent in one turn of a stream, at most 65535. 16384 suits bulk transfers.
      A host's queue holds 4 of them before its streams wait for their turn. (needs a restart to take effect)
      """.trim(),
      """
      Stream relay: batched UDP system calls through libclaj. (needs a restart to take effect)
      Possible values: &lboff&lw, &lbauto&lw, or &lbjni&lw to warn when unavailable.
      """.trim()
  ).reverse();

  /** Must be set at initialization and not modified after. {@code -1} means not initialized. */
  public static int serverVersion = -1;

  public static Field<Boolean> debug = new Field<>("debug", fieldDescs.pop(), false,
                                                   v -> Log.level = v ? LogLevel.debug : LogLevel.info);
  public static Field<LogLevel> writeLogLevel = new Field<>("write-log-level", fieldDescs.pop(), LogLevel.err);
  public static Field<Integer> maxConnections = new Field<>("max-connections", fieldDescs.pop(), 1<<20);
  public static Field<Integer> connectionLimit = new Field<>("connection-limit", fieldDescs.pop(), 1<<6);
  public static Field<Integer> registerTimeout = new Field<>("register-timeout", fieldDescs.pop(), 5);
  public static Field<Integer> maxRooms = new Field<>("max-rooms", fieldDescs.pop(), 1<<16);
  public static Field<Integer> roomLimit = new Field<>("room-limit", fieldDescs.pop(), 16);
  public static Field<Integer> clientLimit = new Field<>("client-limit", fieldDescs.pop(), 1<<7);
  public static Field<Float> clientRate = new Field<>("client-rate", fieldDescs.pop(), 2f).range(0, 100_000);
  public static Field<Float> hostRate = new Field<>("host-rate", fieldDescs.pop(), 2f).range(0, 100_000);
  public static Field<Integer> joinLimit = new Field<>("join-limit", fieldDescs.pop(), 32);
  public static Field<Integer> infoLimit = new Field<>("info-limit", fieldDescs.pop(), 32);
  public static Field<Integer> listLimit = new Field<>("list-limit", fieldDescs.pop(), 32);
  public static Field<Integer> stateLimit = new Field<>("state-limit", fieldDescs.pop(), 24);
  public static Field<Integer> infoRequestLimit = new Field<>("info-request-limit", fieldDescs.pop(), 1<<7);
  public static Field<Integer> listRequestLimit = new Field<>("list-request-limit", fieldDescs.pop(), 1<<10);
  public static Field<Boolean> acceptNoType = new Field<>("accept-no-type", fieldDescs.pop(), true);
  public static Field<Boolean> warnDeprecated = new Field<>("warn-deprecated", fieldDescs.pop(), true);
  public static Field<Boolean> warnClosing = new Field<>("warn-closing", fieldDescs.pop(), true);
  public static Field<Integer> closeWait = new Field<>("close-wait", fieldDescs.pop(), 10);
  public static Field<Integer> stateLifetime = new Field<>("state-lifetime", fieldDescs.pop(), 60);
  public static Field<Integer> stateTimeout = new Field<>("state-timeout", fieldDescs.pop(), 20);
  public static Field<Integer> listLifetime = new Field<>("list-lifetime", fieldDescs.pop(), 60);
  public static Field<Integer> listTimeout = new Field<>("list-timeout", fieldDescs.pop(), 30);
  public static Field<Integer> raterLifetime = new Field<>("rater-lifetime", fieldDescs.pop(), 5 * 60);
  public static Field<Integer> afkTime = new Field<>("afk-time", fieldDescs.pop(), 2 * 60);
  public static Field<Boolean> dualThread = new Field<>("dual-thread", fieldDescs.pop(), true);
  public static SetField<String> blacklist = new SetField<>("blacklist", fieldDescs.pop(), String.class);
  public static SetField<ClajType> typeBlacklist = new SetField<>("type-blacklist", fieldDescs.pop(), ClajType.class);
  public static Field<String> bindAddress = new Field<>("bind-address", fieldDescs.pop(), "");
  public static Field<Integer> streamPort = new Field<>("stream-port", fieldDescs.pop(), 0).range(0, 65535);
  public static Field<Long> memoryBudget = new Field<>("memory-budget", fieldDescs.pop(), 0L)
    .range(0, Long.MAX_VALUE);
  public static Field<Float> controlTimeout = new Field<>("control-timeout", fieldDescs.pop(), 30f).positive();
  public static Field<Float> lingerTimeout = new Field<>("linger-timeout", fieldDescs.pop(), 5f).positive();
  public static Field<Float> idleTimeout = new Field<>("idle-timeout", fieldDescs.pop(), 12f).range(0, 86400);
  public static Field<Integer> keepAlive = new Field<>("keep-alive", fieldDescs.pop(), 10).range(0, 21844);
  public static Field<Integer> maxRequests = new Field<>("max-requests", fieldDescs.pop(), 32)
    .range(1, Integer.MAX_VALUE);
  public static Field<Integer> sliceSize = new Field<>("slice-size", fieldDescs.pop(), 4 * 1024).range(1, 65535);
  public static Field<String> udpBatching = new Field<>("udp-batching", fieldDescs.pop(), "auto")
    .check("must be off, auto or jni", v -> v.equals("off") || v.equals("auto") || v.equals("jni"));

  static { fieldDescs = null; } // remove cached desc fields
}
