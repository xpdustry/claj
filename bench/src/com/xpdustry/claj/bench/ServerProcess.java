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

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import arc.files.Fi;
import arc.struct.OrderedMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.OS;
import arc.util.Strings;
import arc.util.Threads;
import arc.util.Time;

import picocli.CommandLine.Option;


/** A local CLaJ server started by the bench, without the limits that would kick it. */
final class ServerProcess implements AutoCloseable {
  static final OrderedMap<String, Object> LEGACY_CONFIG = OrderedMap.of(
    "debug", false, "spam-limit", 0, "warn-deprecated", true, "warn-closing", false
  );
  static final OrderedMap<String, Object> MODERN_CONFIG = OrderedMap.of(
    "debug", false, "max-connections", 0, "connection-limit", 0, "register-timeout", 30, "max-rooms", 0,
    "room-limit", 0, "client-limit", 0, "spam-limit", 0, "host-spam-limit", 0, "join-limit", 0, "afk-time", 0,
    "warn-closing", false
  );

  /** {@code --server <jar> [--label <name>] [--set <key=value>]...} */
  static final class Spec {
    @Option(names = "--server", required = true, paramLabel = "<jar>", description = "Server jar to start.")
    Path jar;

    @Option(names = "--label", paramLabel = "<name>", description = "Name in the results. (default: its version)")
    String label;

    @Option(names = "--set", paramLabel = "<key=value>",
            description = "Overrides a value of its config.json, e.g. dual-thread=false.")
    Map<String, String> config = new LinkedHashMap<>();

    Proto proto;

    /** Reads the version from the jar manifest. */
    Spec resolve() throws IOException {
      jar = jar.toAbsolutePath();
      String version;
      try (JarFile f = new JarFile(jar.toFile())) {
        version = f.getManifest() == null ? null : f.getManifest().getMainAttributes().getValue("Claj-Version");
      }
      if (version == null) throw new IllegalArgumentException("no Claj-Version in the manifest of " + jar);
      proto = Proto.fromVersion(version);
      if (label == null) label = version + (config.isEmpty() ? "" : " " + config);
      return this;
    }

    OrderedMap<String, Object> serverConfig() {
      OrderedMap<String, Object> m = new OrderedMap<>();
      m.putAll(proto.legacy ? LEGACY_CONFIG : MODERN_CONFIG);
      config.forEach((k, v) -> m.put(k, v.equals("true") || v.equals("false") ? Boolean.valueOf(v) :
                                        Strings.canParseInt(v) ? (Object)Integer.valueOf(v) :
                                        Strings.canParseFloat(v) ? (Object)Float.valueOf(v) : v));
      return m;
    }
  }

  final Fi dir;
  final Process process;
  final int port;

  private ServerProcess(Fi dir, Process process, int port) {
    this.dir = dir;
    this.process = process;
    this.port = port;
  }

  /** Starts the server and waits until it accepts connections. */
  static ServerProcess start(Spec spec, int port, Fi dir, List<String> jvmArgs, String cpus) throws Exception {
    dir.mkdirs();
    dir.child("config.json").writeString(Report.JSON.prettyPrint(spec.serverConfig()));

    Seq<String> cmd = new Seq<>(String.class);
    if (cpus != null) {
      if (!OS.isLinux) throw new IllegalStateException("--server-cpus needs taskset, so Linux");
      cmd.add("taskset", "-c", cpus);
    }
    cmd.add(ProcessHandle.current().info().command().orElse("java"));
    cmd.addAll(jvmArgs);
    cmd.add("-Dfile.encoding=UTF-8", "-jar", spec.jar.toString(), String.valueOf(port));

    Process p = new ProcessBuilder(cmd.list())
      .directory(dir.file())
      .redirectErrorStream(true).redirectOutput(dir.child("server.log").file())
      .start();
    Log.info("Server started, pid @: @", p.pid(), cmd.toString(" "));
    ServerProcess server = new ServerProcess(dir, p, port);
    server.awaitReady(Duration.ofSeconds(30));
    return server;
  }

  private void awaitReady(Duration timeout) throws Exception {
    long deadline = Time.nanos() + timeout.toNanos();
    while (Time.nanos() < deadline) {
      if (!process.isAlive()) throw new IOException("server exited with code " + process.exitValue() + ", see " + logFile());
      if (logFile().exists() && logFile().readString().contains("hosted on port")) {
        try (Socket s = new Socket()) { // really accepting connections
          s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
          Threads.sleep(500);
          return;
        } catch (IOException ignored) {}
      }
      Threads.sleep(200);
    }
    throw new IOException("server not ready after " + timeout.toSeconds() + "s, see " + logFile());
  }

  Fi logFile() {
    return dir.child("server.log");
  }

  /** Total CPU time, or {@code -1}. */
  long cpuNanos() {
    return process.toHandle().info().totalCpuDuration().map(Duration::toNanos).orElse(-1L);
  }

  /** Resident memory in MB (Linux only), or {@code null}. */
  Double rssMb() {
    Fi status = new Fi("/proc/" + process.pid() + "/status");
    if (!status.exists()) return null;
    for (String line : status.readString().split("\n")) {
      if (line.startsWith("VmRSS:")) return Strings.parseLong(line.replaceAll("\\D", ""), 0) / 1024.0;
    }
    return null;
  }

  /** Streamed, as the log can be big. */
  int logErrors() {
    if (!logFile().exists()) return -1;
    try (BufferedReader in = logFile().reader(8192, "UTF-8")) {
      int n = 0;
      for (String line; (line = in.readLine()) != null;) {
        if (line.contains("[E]") || line.contains("Exception")) n++;
      }
      return n;
    } catch (IOException e) { return -1; }
  }

  /** Asks the server to exit, then kills it if needed. */
  @Override
  public void close() {
    if (!process.isAlive()) return;
    try (OutputStream in = process.getOutputStream()) {
      in.write("exit\n".getBytes(Strings.utf8));
    } catch (IOException ignored) {}
    try {
      if (process.waitFor(8, TimeUnit.SECONDS)) return;
      Log.warn("Server did not exit in time, killing it.");
      process.destroy();
      if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
