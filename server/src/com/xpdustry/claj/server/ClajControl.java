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

import java.util.Scanner;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.func.Cons2;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Threads;
import arc.util.Time;

import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.status.CloseReason;
import com.xpdustry.claj.common.util.Strings;
import com.xpdustry.claj.common.util.Structs;
import com.xpdustry.claj.server.plugin.Plugins;
import com.xpdustry.claj.server.util.NetworkSpeed;


public class ClajControl extends CommandHandler implements ApplicationListener {
  private String suggested;
  private Thread input;

  public ClajControl() {
    super("");
  }

  /** Start a new daemon thread listening {@link System#in} for commands. */
  @Override
  public void init() {
    registerCommands();

    Events.run(ClajEvents.ServerLoadedEvent.class, () ->
      input = Threads.daemon("Server Control", () -> {
        try (Scanner scanner = new Scanner(System.in)) {
          while (scanner.hasNext()) {
            String line = scanner.nextLine();
            Core.app.post(() -> {
              try {
                ClajVars.logToFile("> " + line); //TODO: log commands too?
                handleCommand(line);
              } catch (Throwable e) { Log.err(e); }
            });
          }
        } catch (Throwable e) { Log.err("Server Control", e); }
      })
    );
  }

  @Override
  public void dispose() {
    if (input != null) input.interrupt();
  }

  public void handleCommand(String line){
    CommandResponse response = handleMessage(line);

    switch (response.type) {
      case unknownCommand:
        int minDst = 0;
        Command closest = null;

        for (Command command : getCommandList()) {
          int dst = Strings.levenshtein(command.text, response.runCommand);
          if (dst < 3 && (closest == null || dst < minDst)) {
            minDst = dst;
            closest = command;
          }
        }

        if (closest != null) {
          Log.err("Command not found. Did you mean \"@\"?", closest.text);
          suggested = line.replace(response.runCommand, closest.text);
        } else Log.err("Invalid command. Type @ for help.", "'help'");
        break;

      case fewArguments:
      case manyArguments:
        Log.err("Too " + response.type.name().replace("Arguments", "") + " command arguments. Usage: @",
                response.command.text + " " + response.command.paramText);
        break;

      case valid:
        suggested = null;
        //$FALL-THROUGH$

      default:
    }
  }

  @SuppressWarnings("unchecked")
  public void registerCommands() {
    register("help", "[command]", "Display the command list.", args -> {
      if (args.length == 0) {
        Log.info("Commands: [total: @]", getCommandList().size);
        getCommandList().each(c ->
          Log.info("&lk|&fr &b&lb" + c.text + (c.paramText.isEmpty() ? "" : " &lc&fi") + c.paramText +
                   "&fr - &lw" + c.description));
        return;
      }
      Command c = getCommandList().find(t -> t.text.equals(args[0]));
      if (c == null) Log.err("Command not found.");
      else {
        Log.info(Strings.capitalize(c.text) + ":");
        Log.info("&lk|&fr &b&lb" + c.text + (c.paramText.isEmpty() ? "" : " &lc&fi") + c.paramText +
                 "&fr - &lw" + c.description);
      }
    });

    register("status", "Display status of server and rooms.", _ -> {
      ClajStateSummary state = ClajStateSummary.now();
      Log.info("CLaJ Node Status:");
      Log.info("&lk|&fr Version: CLaJ @ (@), Java @", state.version, state.majorVersion, state.javaVersion);
      Log.info("&lk|&fr Uptime: @", Strings.formatDuration(state.uptime, true));
      boolean sa = Core.app instanceof ServerApplication;
      Log.info("&lk|&fr Main TPS: @" + (sa ? " (@ awaiting)" : ""), state.mainTps,
               sa ? ((ServerApplication)Core.app).waitingTasks() : -1);
      Log.info("&lk|&fr Net TPS: @", state.netTps);
      Log.info("&lk|&fr Memory: @ / @ (@)", Strings.formatBytes(state.usedMemory),
               Strings.formatBytes(state.allocatedMemory), Strings.formatBytes(state.maxMemory));
      Log.info("&lk|&fr Buffers: @ / @", Strings.formatBytes(state.usedBuffer), Strings.formatBytes(state.maxBuffer));
      Log.info("&lk|&fr CPU: @ (@)", String.format("%.2f%%", state.javaCpuLoad),
               String.format("%.2f%%", state.systemCpuLoad));
      Log.info("&lk|&fr Disk: @ / @ (@)", Strings.formatBytes(state.usedDisk), Strings.formatBytes(state.freeDisk),
               Strings.formatBytes(state.maxDisk));
      Log.info("&lk|&fr Load: @ rooms, @ clients, @ connections.", state.rooms, state.clients, state.connections);
      if (ClajVars.relay.networkSpeed == null) {
        Log.info("&lk|&fr Network speed calculator is disabled.");
        return;
      }
      Log.info("&lk|&fr Network: @/s s up, @/s down (@ up, @ down)", Strings.formatBytes(state.uploadSpeed),
               Strings.formatBytes(state.downloadSpeed), Strings.formatBytes(state.totalUpload),
               Strings.formatBytes(state.totalDownload));
      Log.info("&lk|&fr Packets: @ p/s up, @ p/s down (@ up, @ down)", state.uploadTransfert, state.downloadTransfert,
               state.totalTransfertUpload, state.totalTransfertDownload);
    });

    register("state", "[close|joins|rooms] [on|off]", "Set node state.", args -> {
      if (args.length == 0) {
        Log.info("Node is @.", ClajVars.relay.isClosed() ? "closed" : "open");
        Log.info("New rooms are @.", ClajVars.relay.blockCreate ? "denied" : "allowed");
        Log.info("Room joins are @.", ClajVars.relay.blockJoin ? "denied" : "allowed");
        return;
      }

      boolean close = false, joins = false, rooms = false, on = false;
      switch (args[0]) {
        case "close" -> close = true;
        case "joins" -> joins = true;
        case "rooms" -> rooms = true;
        default -> {
          Log.err("Invalid argument! Must be 'close', 'joins', 'rooms', or nothing.");
          return;
        }
      }
      if (args.length == 1 || !(on = Strings.isTrue(args[1])) && !Strings.isFalse(args[1])) {
        Log.err((args.length == 1 ? "Missing" : "Invalid") + " argument! Must be 'on' or 'off'.");
        return;
      }

      if (close) {
        ClajVars.relay.setClose(on);
        Log.info("Server @ to new connections and rooms.", on ? "closed" : "opened");
      } else if (joins) {
        ClajVars.relay.blockJoin = !on;
        Log.info("Future room join requests will be @.", on ? "allowed" : "denied");
      } else if (rooms) {
        ClajVars.relay.blockCreate = !on;
        Log.info("New room creation requests will be @.", on ? "allowed" : "denied");
      }
    });

    register("gc", "Trigger a garbage collection.", _ -> {
      long pre = Core.app.getJavaHeap();
      System.gc();
      long post = Core.app.getJavaHeap();
      Log.info("@ collected. Heap usage now at @.", Strings.formatBytes(pre - post), Strings.formatBytes(post));
    });

    register("yes", "Run the last suggested incorrect command.", _ -> {
      if(suggested != null) handleCommand(suggested);
      else Log.err("There is nothing to say yes to.");
    });

    //TODO: add argument to delay closure or waits until all room closed
    register("exit", "Stop the server.", _ -> {
      Log.info("Shutting down CLaJ server.");
      ClajVars.relay.stop(Core.app::exit);
    });

    register("plugins", "[name...]", "Display loaded plugins or information of a specific one.", args -> {
      if (args.length == 0) {
         if (!ClajVars.plugins.list().isEmpty()) {
          Log.info("Plugins: [total: @]", ClajVars.plugins.list().size);
          ClajVars.plugins.list().each(p ->
            Log.info("&lk|&fr @ &fi@&fr (@)" + (p.enabled() ? "" : " &lr(" + p.state + ")"), p.meta.displayName,
                     p.meta.version, p.name));

        } else Log.info("No plugins found.");
        Log.info("Plugin directory: &fi@", ClajVars.pluginsDirectory.file().getAbsoluteFile().toString());
        return;
      }

      Plugins.LoadedPlugin plugin = ClajVars.plugins.list().find(p -> p.meta.name.equalsIgnoreCase(args[0]));
      if (plugin != null) {
        Log.info("Name: @", plugin.meta.displayName);
        Log.info("Internal Name: @", plugin.name);
        Log.info("Version: @", plugin.meta.version);
        Log.info("Author: @", plugin.meta.author);
        Log.info("Path: @", plugin.file.path());
        Log.info("Description: @", plugin.meta.description);
        Log.info("State: @", Strings.camelToKebab(plugin.state.name()).replace('-', ' '));
      } else Log.info("No mod with name '@' found.", args[0]);
    });

    register("rooms", "[info]", "Displays created rooms.", args -> {
      if (ClajVars.relay.rooms.isEmpty()) {
        Log.info("No created rooms.");

      } else if (args.length == 0) {
        Log.info("Rooms: [total: @]", ClajVars.relay.rooms.size);
        ClajVars.relay.rooms.eachValue(r -> {
          NetworkSpeed n = r.transferredPackets;
          Log.info("&lk|&fr @ @: @ client" + (r.isEmpty() ? "" : "s") +
                   " (@). @ p/s in, @ p/s out (@ in, @ out).",
                   r.isPublic ? "&lg[O]" : "&lr[P]", r.sid, r.clients() + 1,
                   Strings.formatDuration(Time.timeSinceMillis(r.createdAt), true),
                   n.uploadSpeed(), n.downloadSpeed(), n.totalUpload(), n.totalDownload());
        });

      } else if (args[0].equals("info")) {
        Log.info("Rooms: [total: @]", ClajVars.relay.rooms.size);
        ClajVars.relay.rooms.eachValue(r -> {
          Log.info("&lk|&fr Room @: [@ client" + (r.isEmpty() ? "" : "s") + ", type: @, @]", r.sid,
                   r.clients() + 1, r.type, r.isPublic ? "&lgpublic" : "&lrprivate");
          Log.info("&lk| |&fr [H] Connection @&fr - @", r.host.sid, r.host.saddress);
          r.clients.each(c -> Log.info("&lk| |&fr [C] Connection @&fr - @", c.sid, c.saddress));
          Log.info("&lk|&fr");
        });

      } else {
        Log.err("Invalid argument! Must be 'info' or nothing.");
      }
    });
/*TODO
    register("room", "<id>", "Display all info about a room", args -> {

    });
*/
    register("close", "<room|list|all> <id|type|reason> [reason]", "Close a room or all rooms.", args -> {
      switch (args[0]) {
        case "room":
          ClajRoom room = getRoom(args[1]);
          if (room == null) return;
          if (args.length == 2) {
            ClajVars.relay.closeRoom(room);
            Log.info("Room @ closed.", room.sid);
            return;
          }
          CloseReason reason = getReason(args[2]);
          if (reason == null) return;
          ClajVars.relay.closeRoom(room, reason);
          Log.info("Room @ closed for reason @.", room.sid, reason);
          break;

        case "list":
          ClajType type = getType(args[1]);
          if (type == null) return;
          if (args.length == 2) {
            int closed = ClajVars.relay.closeRoomList(type);
            Log.info("@ rooms of type @ have been closed.", closed, type);
            return;
          }
          reason = getReason(args[2]);
          if (reason == null) return;
          int closed = ClajVars.relay.closeRoomList(type, reason);
          Log.info("@ rooms of type @ have been closed for reason @.", closed, type, reason);
          break;

        case "all":
          reason = getReason(args[1]);
          if (reason == null) return;
          int rooms = ClajVars.relay.rooms.size;
          ClajVars.relay.closeRooms(reason);
          Log.info("@ rooms have been closed for reason @.", rooms, reason);
          break;

        default:
          Log.err("Invalid argument! Must be 'room', 'list' or 'all'.");
      }
    });

    register("refresh", "<room|list> [id|type] [force]", "Refresh a room state or a room list.", args -> {
      switch (args[0]) {
        case "room":
          if (args.length == 1) {
            Log.err("A room id must be provided. (e.g. @)", "Q1w2E3r4T5y=");
            return;
          }

          ClajRoom room = getRoom(args[1]);
          if (room == null) return;

          boolean force = args.length == 3 ? args[2].equals("force") : false;
          if (!force && args.length == 3)
            Log.err("Invalid argument! Must be 'force'.");
          else if (!force && !room.isPublic)
            Log.err("The room is not public, state cannot be requested. (Use 'force' argument to request anyway)");
          else if (!force && !room.canRequestState)
            Log.err("The room doesn't want his state to be requested. (Use 'force' argument to request anyway)");
          else if (room.requestState())
            Log.info("State of room @ has been requested.", room.sid);
          else
            Log.info("A request is already pending, please wait a moment.");
          break;

        case "list":
          if (args.length == 1) {
            if (ClajVars.relay.types.isEmpty()) Log.info("No created rooms.");
            else {
              ClajVars.relay.refreshRoomLists();
              Log.info("Refreshing room lists... This can be long.");
            }
            return;
          }

          ClajType type = getType(args[1]);
          if (type == null) return;

          force = args.length == 3 ? args[2].equals("force") : false;
          if (!force && args.length == 3)
            Log.err("Invalid argument! Must be 'force'.");
          else if (ClajVars.relay.refreshRoomList(type, force))
            Log.info("Refreshing room list of type @... This can take a moment.", type);
          else
            Log.info("A refresh is already in progress, please wait a moment. "
                   + "(Use 'force' argument to refresh anyway)");
          break;

        default:
          Log.err("Invalid argument! Must be 'room' or 'list'.");
      }
    });

    register("config", "[reload|name] [default|set|add|remove|clear] [value...]", "Configure server settings.",
    args -> {
      if (args.length == 0) {
        Log.info("Config values: [total: @]", ClajConfig.all.size);
        ClajConfig.all.each(f -> {
          boolean first = true;
          for (String line : f.toString().split("\n")) {
            if (first) Log.info("&lk|&fr @: &lc&fi" + line, f.key);
            else Log.info("&lk|&fr &lc&fi" + line);
            first = false;
          }
          for (String line : f.desc.split("\n"))
            Log.info("&lk| |&lw " + line);
          Log.info("&lk|&fr");
        });
        return;
      } else if (args[0].equals("reload")) {
        try {
          ClajConfig.reload();
          Log.info("Configuration successfully reloaded.");
        } catch (Exception e) {
          Log.err(e.getMessage(), e.getCause() == null ? e : e.getCause());
        }
        return;
      }

      ClajConfig.Field<Object> field = ClajConfig.get(args[0]);
      Object value;

      if (field == null) {
        Log.err("No setting named '@' found.", args[0]);
        return;
      } else if (args.length == 1) {
        Log.info("'@' is currently at @.", field.key, "&lc&fi" + field);
        Log.info("Default value is: @.", field.defaultValue);
        return;
      }

      switch (args[1]) {
        case "default":
          field.setDefault();
          Log.info("'@' set to default value.", field.key);
          break;

        case "set":
          value = getValue(field, args, 2, false);
          if (value == null) return;
          field.set(value);
          Log.info("'@' set to @.", field.key, value);
          break;

        case "add":
          if (!(field instanceof ClajConfig.SetField set)) {
            Log.err("Invalid operation for '@'.", field.key);
            return;
          }
          value = getValue(field, args, 2, true);
          if (value == null) return;
          if (set.add(value)) Log.info("@ added to '@'.", value, field.key);
          else Log.err("@ not in '@'.", value, field.key);
          break;

        case "remove":
          if (!(field instanceof ClajConfig.SetField set)) {
            Log.err("Invalid operation for '@'.", field.key);
            return;
          }
          value = getValue(field, args, 2, true);
          if (value == null) return;
          if (set.remove(value)) Log.info("@ removed from '@'.", value, field.key);
          else Log.err("@ not in '@'.", value, field.key);
          break;

        case "clear":
          if (!(field instanceof ClajConfig.SetField set)) {
            Log.err("Invalid operation for '@'.", field.key);
            return;
          }
          set.clear();
          Log.info("Cleared '@' list.", field.key);
          break;

        default:
          Log.err("Invalid argument! Must be 'default', 'set', 'add', 'remove' or 'clear'.");
      }
    });

    register("say", "<room|list|all> <id|type|text> [text...]", "Send a message to room(s) client(s).",
             notifyCommand("Message", ClajRoom::message));

    register("alert", "<room|list|all> <id|type|text> [text...]", "Send a popup message to room(s) host.",
             notifyCommand("Popup", ClajRoom::popup));
  }


  private Cons<String[]> notifyCommand(String actionName, Cons2<ClajRoom, String> action) {
    return args -> {
      switch (args[0]) {
        case "room":
          ClajRoom room = getRoom(args[1]);
          if (room == null) return;
          if (args.length == 2) {
            Log.err("Missing 'text' argument!");
            return;
          }
          action.get(room, args[2]);
          Log.info(actionName + " sent to the host of room @.", args[1]);
          break;

        case "list":
          ClajType type = getType(args[1]);
          if (type == null) return;
          if (args.length == 2) {
            Log.err("Missing 'text' argument!");
            return;
          }
          ClajVars.relay.types.get(type).eachValue(r -> action.get(r, args[2]));
          Log.info(actionName + " sent to @ rooms of type @.", ClajVars.relay.types.get(type).size, type);
          break;

        case "all":
          String text = Strings.join(" ", args, 1, args.length);
          ClajVars.relay.rooms.eachValue(r -> action.get(r, text));
          Log.info(actionName + " sent to @ rooms.", ClajVars.relay.rooms.size);
          break;

        default:
          Log.err("Invalid argument! Must be 'room', 'list' or 'all'.");
      }
    };
  }

  protected ClajRoom getRoom(String arg) {
    ClajRoom room = ClajVars.relay.getRoom(arg);
    if (room == null) Log.err("Room @ not found.", arg);
    return room;
  }

  protected ClajType getType(String arg) {
    ClajType type = ClajType.of(arg);
    if (type == null) Log.err("Invalid CLaJ type.");
    else if (!ClajVars.relay.types.containsKey(type)) {
      Log.err("No room with type @ found.", type);
      type = null;
    }
    return type;
  }

  protected CloseReason getReason(String arg) {
    CloseReason reason = Structs.find(CloseReason.all, r -> r.name().equalsIgnoreCase(arg));
    if (reason == null) {
      Log.err("No reason named '@' found. Must be one of: @", arg,
              Strings.toSentence(Structs.iterable(CloseReason.all), CloseReason::name));
    }
    return reason;
  }

  @SuppressWarnings("rawtypes")
  protected Object getValue(ClajConfig.Field<Object> field, String[] args, int from, boolean isElement) {
    if (args.length <= from) {
      Log.err("Missing 'value' argument.");
      return null;
    }
    String value = Strings.join(" ", args, from, args.length);
    if (value.equalsIgnoreCase("null")) {
      Log.err("Value cannot be null.");
      return null;
    }
    try { return isElement ? ((ClajConfig.SetField)field).decodeElement(value) : field.decode(value); }
    catch (IllegalArgumentException e) {
      String m = e.getMessage();
      Log.err("Invalid value for setting '@'" + (m != null ? ": @." : "."), field.key, m);
      return null;
    }
  }
}