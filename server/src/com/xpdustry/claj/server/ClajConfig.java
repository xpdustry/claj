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
import arc.func.Cons;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Structs;
import arc.util.serialization.*;

import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.server.util.Autosaver;
import com.xpdustry.claj.server.util.JsonSettings;


public class ClajConfig {
  public static final Seq<Field<?>> all = new Seq<>();
  public static String fileName = "config.json";
  protected static JsonSettings settings;
  
  @SuppressWarnings("rawtypes")
  public static void init() {
    settings = new JsonSettings(new Fi(fileName, Files.FileType.local), true, true, true, false);

    settings.addSerializer(ClajType.class, new Json.Serializer<ClajType>() {
      public void write(Json json, ClajType object, Class knownType) { json.writeValue(object.type());}
      public ClajType read(Json json, JsonValue jsonData, Class type) { return ClajType.of(jsonData.asString()); }
    });
  }

  public static void load() {
    if (settings == null) init();
    settings.load();
    all.each(Field::load);
  }

  /** Force save all fields. Do nothing if config was not initialized. */
  public static void save() {
    if (settings == null) return;
    all.each(Field::save);
    settings.save();
  }

  /** Case is ignored. */
  @SuppressWarnings("unchecked")
  public static <T> Field<T> get(String key) {
    return (Field<T>)all.find(f -> f.key.equalsIgnoreCase(key));
  }


  @SuppressWarnings("unchecked")
  public static class Field<T> implements Autosaver.Saveable {
    public final String key, desc;
    public final T defaultValue;
    public final Class<T> type;
    protected Cons<T> changed;
    protected T value;
    protected boolean loaded, modified;

    public Field(String key, String desc, T def) { this(key, desc, def, null, true); }
    public Field(String key, String desc, T def, Cons<T> changed) { this(key, desc, def, changed, true); }
    public Field(String key, String desc, T def, boolean register) { this(key, desc, def, null, register); }
    public Field(String key, String desc, T def, Cons<T> changed, boolean register) {
      if (key == null || (key = key.trim()).isEmpty()) throw new IllegalArgumentException("key is null");
      if (ClajConfig.get(key) != null) throw new IllegalArgumentException("field '" + key + "' already exists");
      this.key = key;
      this.desc = desc == null ? "" : desc;
      this.defaultValue = def;
      this.type = def == null ? null : (Class<T>)def.getClass();
      this.changed = changed == null ? _ -> {} : changed;

      if (register) all.add(this);
      Autosaver.add(this, Autosaver.SavePriority.normal);
    }

    /** @return "field '" + {@link key} + "'". */
    public String name() {
      return "field '" + key + "'";
    }

    public boolean modified() {
      return modified;
    }

    public void load() {
      if (loaded) return;
      if (settings == null || !settings.loaded())
        throw new IllegalStateException("settings not initialized");
      value = (T)settings.getOrPut(key, type, defaultValue);
      loaded = true;
      modified = value == defaultValue;
    }

    public void save() {
      if (settings == null || !settings.loaded())
        throw new IllegalStateException("settings not initialized");
      //Because ObjectSet is not saved as a normal list, and it's annoying
      settings.put(key, value instanceof ObjectSet<?> v ? v.toSeq() : value);
      modified = false;
    }

    public T get() {
      load();
      return value;
    }

    /** Sets the modified flag for a direct save. */
    public T getChange() {
      T v = get();
      modified = true;
      return v;
    }

    public void set(T value) {
      this.value = value;
      modified = true;
      changed.get(value);
    }

    public void setDefault() {
      set(defaultValue);
    }

    public boolean isDefault() {
      return Structs.eq(get(), defaultValue);
    }

    /** Adds changed callback. */
    public void changed(Cons<T> changed) {
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

    /**
     * Tries to decode the value from a {@link String} by parsing and decoding as Json.
     * @throws IllegalArgumentException if decoding failed, or decoded type is not the same.
     */
    public T decode(String value) throws IllegalArgumentException {
      return (T)switch (defaultValue) {
        case null -> value.equals("null");
        // Nothing to decode
        case String _ -> value;
        // Handle boolean manually for more matching cases
        case Boolean _ -> {
          if (Strings.isTrue(value)) yield true;
          else if (Strings.isFalse(value)) yield false;
          else throw new IllegalArgumentException("invalid boolean value");
        }
        default -> {
          try {
            T decoded = settings.getJson().fromJson(type, value);
            if (decoded == null)
              throw new IllegalArgumentException("decoded value cannot be null");
            if (decoded.getClass() != type)
              throw new IllegalArgumentException("decoded type must be a " + type.getName());
            yield decoded;
          } catch (SerializationException e) {
            throw new IllegalArgumentException(e);
          }
        }
      };
    }
  }


  private static Seq<String> fieldDescs = Seq.with(
      "Toggle debug log level",
      "Maximum number of connections (not clients) allowed on this server. Set to &lb0&lw to disable.",
      "Maximum number of rooms that can be created on this server. Set to &lb0&lw to disable.",
      "Maximum number of clients allowed per room. Set to &lb0&lw to disable.",
      """
      Limit for packet count sent within 3 sec that will lead to a disconnect.
      Ignored for room hosts.
      """.trim(),
      """
      Limit of room join requests per minute per ip address. The server will act as the room is not found.
      This can prevent room searching. Set to &lb0&lw to disable.
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
      "The time after which a new room status will be requested, if necessary. (in ms)",
      "The time to wait for the room's new status. (in ms)",
      "The time after which a new status will be requested to all rooms, if necessary. (in ms)",
      """
      Listing public rooms can be very long when requesting states,
      this defines the time before the list is send as is, even some state was not received.
      """.trim()
  ).reverse();

  public static Field<Boolean> debug = new Field<>("debug", fieldDescs.pop(), false, v ->
                                                   Log.level = v ? Log.LogLevel.debug : Log.LogLevel.info);
  public static Field<Integer> maxConnections = new Field<>("max-connections", fieldDescs.pop(), 1<<23);
  public static Field<Integer> roomLimit = new Field<>("room-limit", fieldDescs.pop(), 1<<16);
  public static Field<Short> clientLimit = new Field<>("client-limit", fieldDescs.pop(), (short)(1<<8));
  public static Field<Integer> spamLimit = new Field<>("spam-limit", fieldDescs.pop(), 300);
  public static Field<Integer> joinLimit = new Field<>("join-limit", fieldDescs.pop(), 32);
  public static Field<Integer> infoLimit = new Field<>("info-limit", fieldDescs.pop(), 32);
  public static Field<Integer> listLimit = new Field<>("list-limit", fieldDescs.pop(), 32);
  public static Field<Integer> infoRequestLimit = new Field<>("info-request-limit", fieldDescs.pop(), 1<<7);
  public static Field<Integer> listRequestLimit = new Field<>("list-request-limit", fieldDescs.pop(), 1<<10);
  public static Field<Boolean> acceptNoType = new Field<>("accept-no-type", fieldDescs.pop(), true);
  public static Field<Boolean> warnDeprecated = new Field<>("warn-deprecated", fieldDescs.pop(), true);
  public static Field<Boolean> warnClosing = new Field<>("warn-closing", fieldDescs.pop(), true);
  public static Field<Float> closeWait = new Field<>("close-wait", fieldDescs.pop(), 10f);
  public static Field<Integer> stateLifetime = new Field<>("state-lifetime", fieldDescs.pop(), 60 * 1000);
  public static Field<Integer> stateTimeout = new Field<>("state-timeout", fieldDescs.pop(), 20 * 1000);
  public static Field<Integer> listLifetime = new Field<>("list-lifetime", fieldDescs.pop(), 60 * 1000);
  public static Field<Integer> listTimeout = new Field<>("list-timeout", fieldDescs.pop(), 30 * 1000);

  // Other fields having their own command
  public static Field<ObjectSet<String>> blacklist = new Field<>("blacklist", "", new ObjectSet<>(8), false);
  public static Field<ObjectSet<ClajType>> typeBlacklist = new Field<>("type-blacklist", "", new ObjectSet<>(8), false);
  
  static { fieldDescs.shrink(); } // remove cached fields desc
}
