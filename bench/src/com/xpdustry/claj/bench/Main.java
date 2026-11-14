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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Threads;
import arc.util.Log.LogLevel;

import com.xpdustry.claj.bench.Report.Run;

import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;


@Command(name = "claj-bench", mixinStandardHelpOptions = true, versionProvider = Main.class,
         description = "Load tester for CLaJ relay servers. Simulates rooms of hosts and clients, and measures " +
                       "how many packets the server relays, with which latency.",
         subcommands = {Main.RunCommand.class, Main.CompareCommand.class, Main.ConfigCommand.class, HelpCommand.class})
public final class Main implements Runnable, IVersionProvider {
  static final String[] TAGS = {"&lc&fb[D]&fr", "&lb&fb[I]&fr", "&ly&fb[W]&fr", "&lr&fb[E]", ""};
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
  /** Server being benchmarked, shown in logs. Servers are benchmarked one after the other. */
  static volatile String label = "bench";
  static String[] args;

  @Spec CommandSpec spec;

  public static void main(String[] args) {
    Locale.setDefault(Locale.ROOT);
    Main.args = args;
    Log.level = LogLevel.debug;
    Log.useColors = System.console() != null;
    Log.logger = (level, text) -> {
      String line = "&lk&fb[" + LocalTime.now().format(TIME) + "]&fr " + TAGS[level.ordinal()] + " &fb[" + label + "]&fr " + text;
      (level == LogLevel.err ? System.err : System.out).println(Log.useColors ? Log.addColors(line) : Log.removeColors(line));
    };

    System.exit(new CommandLine(new Main())
          .registerConverter(Scenario.Ramp.class, Scenario.Ramp::parse)
          .setCaseInsensitiveEnumValuesAllowed(true)
          .execute(args));
  }

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  @Override
  public String[] getVersion() {
    String v = Main.class.getPackage().getImplementationVersion();
    return new String[]{"claj-bench " + (v == null ? "dev" : v)};
  }

  /** Output options of the commands. */
  static final class Output {
    @Option(names = "--out", paramLabel = "<dir>", description = "Results directory. (default: bench-results/<date>)")
    Path out;

    @Option(names = {"-q", "--quiet"}, description = "Hides the per-second samples.")
    void quiet(boolean quiet) { Log.level = quiet ? LogLevel.info : LogLevel.debug; }

    Fi dir() {
      return out != null ? new Fi(out.toFile()) :
             new Fi("bench-results").child(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    }
  }

  /** Invalid values are usage errors. */
  static Scenario scenario(CommandSpec spec, Scenario.Options options) {
    try { return options.toScenario(); }
    catch (IllegalArgumentException e) { throw new ParameterException(spec.commandLine(), e.getMessage(), e); }
  }

  @Command(name = "run", sortOptions = false, description = "Benchmarks a running server.")
  static final class RunCommand implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Option(names = "--host", paramLabel = "<address>", description = "(default: ${DEFAULT-VALUE})")
    String host = "127.0.0.1";

    @Option(names = "--port", paramLabel = "<port>", description = "(default: ${DEFAULT-VALUE})")
    int port = 7000;

    @Option(names = "--proto", paramLabel = "<version>",
            description = "auto (discovery packet), 2.3 or 2.4. (default: ${DEFAULT-VALUE})")
    String proto = "auto";

    @Option(names = "--label", paramLabel = "<name>", description = "Name of the server in results. (default: the host)")
    String name;

    @Mixin Scenario.Options scenario;
    @Mixin Output output;

    @Override
    public Integer call() throws Exception {
      Scenario sc = scenario(spec, scenario);
      InetSocketAddress address = new InetSocketAddress(host, port);
      if (address.isUnresolved()) throw new ParameterException(spec.commandLine(), "unable to resolve " + host);
      label = name != null ? name : host;
      Proto p;
      try { p = proto.equals("auto") ? Proto.detect(address) : Proto.fromVersion(proto); }
      catch (IllegalArgumentException e) { throw new ParameterException(spec.commandLine(), e.getMessage(), e); }

      Run run = new Bench(label, address, p, sc, null).run();
      Report.printRun(run);
      Report.write(output.dir(), Seq.with(run), args);
      return run.error == null ? 0 : 1;
    }
  }

  @Command(name = "compare", sortOptions = false, description = "Starts each server jar locally, one after the other, " +
                                                                "and runs the same scenario on them.")
  static final class CompareCommand implements Callable<Integer> {
    @Spec CommandSpec spec;

    @ArgGroup(exclusive = false, multiplicity = "1..*", heading = "Servers (repeat for each one):%n")
    List<ServerProcess.Spec> servers;

    @Option(names = "--port", paramLabel = "<port>",
            description = "Port of the first server, then +1 for each. (default: ${DEFAULT-VALUE})")
    int port = 17000;

    @Option(names = "--server-jvm", paramLabel = "<args>", split = " ",
            description = "Server JVM arguments, e.g. --server-jvm=\"-Xmx1g\".")
    List<String> jvm = new ArrayList<>();

    @Option(names = "--server-cpus", paramLabel = "<cpus>",
            description = "Pins the servers on these CPUs with taskset (Linux), e.g. 0-1.")
    String cpus;

    @Option(names = "--cooldown", paramLabel = "<s>", description = "Pause between servers. (default: ${DEFAULT-VALUE})")
    double cooldown = 3;

    @Mixin Scenario.Options scenario;
    @Mixin Output output;

    @Override
    public Integer call() throws Exception {
      Scenario sc = scenario(spec, scenario);
      for (ServerProcess.Spec s : servers) s.resolve();
      Fi out = output.dir();
      Seq<Run> runs = new Seq<>(Run.class);

      for (int i=0; i<servers.size(); i++) {
        if (i > 0) Threads.sleep((long)(cooldown * 1000));
        ServerProcess.Spec s = servers.get(i);
        label = s.label;
        Fi dir = out.child("server-" + i + "-" + s.label.replaceAll("[^\\w.-]", "_"));
        Run run = new Run(s.label, s.proto.toString(), sc);
        try (ServerProcess server = ServerProcess.start(s, port + i, dir, jvm, cpus)) {
          run = new Bench(s.label, new InetSocketAddress("127.0.0.1", port + i), s.proto, sc, server).run();
          run.serverLog = server.logFile().absolutePath();
          Threads.sleep(2000); // the server logs the disconnections
          server.close();
          run.serverShutdownErrors = server.logErrors() - Objects.requireNonNullElse(run.serverErrors, 0);
        } catch (Exception e) {
          Log.err("Failed", e);
          run.error = e.toString();
        }
        runs.add(run);
        Report.printRun(run);
      }
      Report.printComparison(runs);
      Report.write(out, runs, args);
      return runs.contains(r -> r.error != null) ? 1 : 0;
    }
  }

  @Command(name = "config", description = "Prints the server configurations used by 'compare'. Apply them on a server " +
                                          "benchmarked with 'run', to not rate-limit the bench.")
  static final class ConfigCommand implements Runnable {
    @Override
    public void run() {
      System.out.println("2.3.x:\n" + Report.JSON.prettyPrint(ServerProcess.LEGACY_CONFIG));
      System.out.println("\n2.4.x:\n" + Report.JSON.prettyPrint(ServerProcess.MODERN_CONFIG));
    }
  }
}
