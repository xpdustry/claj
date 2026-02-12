/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2025  Xpdustry
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
import arc.math.Mathf;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.OS;
import arc.util.Threads;

import com.xpdustry.claj.common.status.ClajType;
import com.xpdustry.claj.common.util.Strings;
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
              try { handleCommand(line); }
              catch (Throwable e) { Log.err(e); }
            });
          }
        }
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
      case valid: suggested = null;
      default: break;
    }
  }

  public void registerCommands() {
    register("help", "Display the command list.", args -> {
      Log.info("Commands:");
      getCommandList().each(c ->
        Log.info("&lk|&fr &b&lb" + c.text + (c.paramText.isEmpty() ? "" : " &lc&fi") + c.paramText +
                 "&fr - &lw" + c.description));
    });

    register("version", "Display server version info.", args -> {
      Log.info("CLaJ Version: @ (@)", ClajVars.version, ClajVars.version.majorVersion);
      Log.info("Java Version: @", OS.javaVersion);
    });

    register("status", "Display status of server and rooms.", args -> {
      Log.info("@ rooms, @ client" + (ClajVars.relay.conToRoom.size < 2 ? "" : "s") +
               ", @ connection" + (ClajVars.relay.getConnections().length < 2 ? "." : "s."),
               ClajVars.relay.rooms.size, ClajVars.relay.conToRoom.size, ClajVars.relay.getConnections().length);
      Log.info("@ FPS, @ used.", Core.graphics.getFramesPerSecond(), Strings.formatBytes(Core.app.getJavaHeap()));
      NetworkSpeed net = ClajVars.relay.networkSpeed;
      if (net != null) {
        Log.info("Speed: @/s up, @/s down. Total: @ up, @ down.",
                 Strings.formatBytes((long)net.uploadSpeed()), Strings.formatBytes((long)net.downloadSpeed()),
                 Strings.formatBytes(net.totalUpload()), Strings.formatBytes(net.totalDownload()));
      } else Log.info("Network speed calculator is disabled.");

      if (ClajVars.relay.rooms.isEmpty()) {
        Log.info("No created rooms.");
        return;
      }
      Log.info("Rooms:");
      ClajVars.relay.rooms.eachValue(r -> {
        NetworkSpeed n = r.transferredPackets;
        Log.info("&lk|&fr @: @ client" + (r.clients.isEmpty() ? "" : "s") +
                 ". Rate: @ p/s in, @ p/s out. Total: @ packets in, @ packets out.",
                 r.sid, r.clients.size + 1, Mathf.ceil(n.uploadSpeed()), Mathf.ceil(n.downloadSpeed()),
                 n.totalUpload(), n.totalDownload());
      });
    });

    // Why i added this command? it's useless for this kind of project
    register("gc", "Trigger a garbage collection.", args -> {
      long pre = Core.app.getJavaHeap();
      System.gc();
      long post = Core.app.getJavaHeap();
      Log.info("@ collected. Memory usage now at @.", Strings.formatBytes(pre - post), Strings.formatBytes(post));
    });

    register("yes", "Run the last suggested incorrect command.", args -> {
      if(suggested != null) handleCommand(suggested);
      else Log.err("There is nothing to say yes to.");
    });

    register("exit", "Stop the server.", args -> {
      Log.info("Shutting down CLaJ server.");
      ClajVars.relay.stop(Core.app::exit);
    });

    register("plugins", "[name...]", "Display all loaded plugins or information about a specific one.", args -> {
      if (args.length == 0) {
         if (!ClajVars.plugins.list().isEmpty()) {
          Log.info("Plugins: [total: @]", ClajVars.plugins.list().size);
          ClajVars.plugins.list().each(p ->
            Log.info("&lk|&fr @ &fi@" + (p.enabled() ? "" : " &lr(" + p.state + ")"), p.meta.displayName,
                     p.meta.version));

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
      } else Log.info("No mod with name '@' found.", args[0]);
    });

    register("rooms", "Displays created rooms.", args -> {
      if (ClajVars.relay.rooms.isEmpty()) {
        Log.info("No created rooms.");
        return;
      }

      Log.info("Rooms: [total: @]", ClajVars.relay.rooms.size);
      ClajVars.relay.rooms.eachValue(r -> {
        Log.info("&lk|&fr Room @: [@ client" + (r.clients.isEmpty() ? "" : "s") + ", type: @]", r.sid,
                 r.clients.size + 1, r.type);
        Log.info("&lk| |&fr [H] Connection @&fr - @", r.host.sid, r.host.address);
        for (ClajConnection c : r.clients.values())
          Log.info("&lk| |&fr [C] Connection @&fr - @", c.sid, c.address);
        Log.info("&lk|&fr");
      });
    });

    register("refresh", "<room|list> [id|type] [force]", "Refresh a room state or a room list.", args -> {
      switch (args[0]) {
        case "room":
          if (args.length == 1) {
            Log.err("A room id must be provided. (e.g. @)", "Q1w2E3r4T5y=");
            return;
          }

          ClajRoom room = ClajVars.relay.get(args[1]);
          if (room == null) {
            Log.err("Room @ not found.", args[1]);
            return;
          }

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

          byte[] t = args[1].getBytes(Strings.utf8);
          if (t.length < 1 || t.length > ClajType.SIZE) {
            Log.err("Invalid CLaJ type.");
            return;
          }

          ClajType type = new ClajType(t);
          if (!ClajVars.relay.types.containsKey(type)) {
            Log.err("No room with type @ found.", type);
            return;
          }

          force = args.length == 3 ? args[2].equals("force") : false;
          if (!force && args.length == 3)
            Log.err("Invalid argument! Must be 'force'.");
          else if (ClajVars.relay.refreshRoomList(type, force))
            Log.info("Refreshing room list of type @... This can take a moment.", type);
          else
            Log.info("A refresh is already in progress, please wait a moment. (Use 'force' argument to refresh anyway)");
          break;

        default:
          Log.err("Invalid argument! Must be 'room' or 'list'.");
      }
    });

    register("debug", "[on|off]", "Enable/Disable the debug log level.", args -> {
      if (args.length == 0) Log.info("Debug log level is @.", ClajConfig.debug ? "enabled" : "disabled");

      else if (Strings.isFalse(args[0])) {
        Log.level = Log.LogLevel.info;
        ClajConfig.debug = false;
        ClajConfig.save();
        Log.info("Debug log level disabled.");

      } else if (Strings.isTrue(args[0])) {
        Log.level = Log.LogLevel.debug;
        ClajConfig.debug = true;
        ClajConfig.save();
        Log.info("Debug log level enabled.");

      } else Log.err("Invalid argument.");
    });

    register("spam-limit", "[amount]", "Sets packet spam limit. (0 to disable)", args -> {
      if (args.length == 0) {
        if (ClajConfig.spamLimit == 0) Log.info("Current limit: disabled.");
        else Log.info("Current limit: @ packets per @.", ClajConfig.spamLimit, "3 seconds");
        return;
      }

      int limit = Strings.parseInt(args[0]);
      if (limit < 0) {
        Log.err("Invalid input.");
        return;
      }
      ClajConfig.spamLimit = limit;
      if (ClajConfig.spamLimit == 0) Log.info("Packet spam limit disabled.");
      else Log.info("Packet spam limit set to @ packets per @.", ClajConfig.spamLimit, "3 seconds");
      ClajConfig.save();
    });

    register("join-limit", "[amount]", "Sets join request limit. (0 to disable)", args -> {
      if (args.length == 0) {
        if (ClajConfig.joinLimit == 0) Log.info("Current limit: disabled.");
        else Log.info("Current limit: @ requests per @.", ClajConfig.joinLimit, "minute");
        return;
      }

      int limit = Strings.parseInt(args[0]);
      if (limit < 0) {
        Log.err("Invalid input.");
        return;
      }
      ClajConfig.joinLimit = limit;
      if (ClajConfig.joinLimit == 0) Log.info("Join request limit disabled.");
      else Log.info("Join reqest limit set to @ requests per @.", ClajConfig.joinLimit, "minute");
      ClajConfig.save();
    });

    register("blacklist", "[add|del] [IP]", "Manage the IP blacklist.", args -> {
      if (args.length == 0) {
        if (!ClajConfig.blacklist.isEmpty()) {
          Log.info("Blacklist: [total: @]", ClajConfig.blacklist.size);
          Strings.tableify(ClajConfig.blacklist.toSeq(), 70).each(a -> Log.info("&lk|&fr @", a));
        } else Log.info("Blacklist is empty.");

      } else if (args.length == 1) {
        Log.err("Missing IP argument.");

      } else if (args[0].equals("add")) {
        if (ClajConfig.blacklist.add(args[1])) {
          ClajConfig.save();
          Log.info("IP added to blacklist.");
        } else Log.err("IP already blacklisted.");

      } else if (args[0].equals("del")) {
        if (ClajConfig.blacklist.remove(args[1])) {
          ClajConfig.save();
          Log.info("IP removed from blacklist.");
        } else Log.err("IP not blacklisted.");

      } else Log.err("Invalid argument. Must be 'add' or 'del'.");
    });

    register("warn-deprecated", "[on|off]", "Warn the client if it's CLaJ version is obsolete.", args -> {
      if (args.length == 0) {
        Log.info("Warn message when a client using an obsolete CLaJ version: @.",
                 ClajConfig.warnDeprecated ? "enabled" : "disabled");

      } else if (Strings.isFalse(args[0])) {
        ClajConfig.warnDeprecated = false;
        ClajConfig.save();
        Log.info("Warn message disabled.");

      } else if (Strings.isTrue(args[0])) {
        ClajConfig.warnDeprecated = true;
        ClajConfig.save();
        Log.info("Warn message enabled.");

      } else Log.err("Invalid argument.");
    });

    register("warn-closing", "[on|off]", "Warn all rooms when the server is closing.", args -> {
      if (args.length == 0) {
        Log.info("Warn message when closing the server: @.",
                 ClajConfig.warnClosing ? "enabled" : "disabled");

      } else if (Strings.isFalse(args[0])) {
        ClajConfig.warnClosing = false;
        ClajConfig.save();
        Log.info("Warn message disabled.");

      } else if (Strings.isTrue(args[0])) {
        ClajConfig.warnClosing = true;
        ClajConfig.save();
        Log.info("Warn message enabled.");

      } else Log.err("Invalid argument.");
    });

    register("say", "<roomId|all> <text...>", "Send a message to a room or all rooms.", args -> {
      if (args[0].equals("all")) {
        ClajVars.relay.rooms.eachValue(r -> r.message(args[1]));
        Log.info("Message sent to all rooms.");
        return;
      }

      ClajRoom room = ClajVars.relay.get(args[0]);
      if (room != null) {
        room.message(args[1]);
        Log.info("Message sent to room @.", args[0]);
      } else Log.err("Room @ not found.", args[0]);
    });

    register("alert", "<roomId|all> <text...>", "Send a popup message to the host of a room or all rooms.", args -> {
      if (args[0].equals("all")) {
        ClajVars.relay.rooms.eachValue(r -> r.popup(args[1]));
        Log.info("Popup sent to all room hosts.");
        return;
      }

      ClajRoom room = ClajVars.relay.get(args[0]);
      if (room != null) {
        room.popup(args[1]);
        Log.info("Popup sent to the host of room @.", args[0]);
      } else Log.err("Room @ not found.", args[0]);
    });
  }
}