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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;

import arc.files.Fi;
import arc.func.Func;
import arc.struct.*;
import arc.util.Reflect;
import arc.util.Strings;
import arc.util.serialization.Json;
import arc.util.serialization.JsonWriter.OutputType;


/** Results, console tables and result files. */
final class Report {
  /** Plain JSON, without the type wrappers Arc adds to values of unknown types. */
  static final Json JSON = new Json(OutputType.json) {
    {
      setUsePrototypes(false);
      setTypeName(null);
    }

    @Override
    public void writeValue(Object value, Class knownType, Class elementType) {
      if (!(value instanceof String || value != null && Reflect.isWrapper(value.getClass()))) {
        super.writeValue(value, knownType, elementType);
        return;
      }
      try { getWriter().value(value); }
      catch (IOException e) { throw new UncheckedIOException(e); }
    }
  };

  private Report() {}

  // region results

  enum Verdict {
    PASS,
    FAIL,
    /** The bench itself was the bottleneck, so the step says nothing about the server. */
    BENCH_BOUND,
  }

  /** Measures of one step. Latencies in milliseconds, server values {@code null} when unknown. */
  record Step(
    String name, int rooms, int clients, int connections, double growSeconds, boolean growTimeout,
    double joinFailuresPct, double target, int window, double seconds,
    double sentPps, double relayedPps, double relayedMbps, double deliveryPct,
    double p50, double p90, double p99, double p999, double max, double mean,
    double blockedPct, double laggedPct, long disconnects, long errors, double idlePps, double controlPps,
    Double serverCpu, Double serverCpuMax, Double serverRssMb, double benchBusyPct,
    Verdict verdict, Seq<String> reasons, OrderedMap<String, Integer> joinFailureReasons,
    OrderedMap<String, Integer> disconnectReasons
  ) {
    boolean passed() {
      return verdict == Verdict.PASS;
    }

    String verdictText() {
      return reasons.isEmpty() ? verdict.name() : verdict + " (" + reasons.toString(", ") + ")";
    }
  }

  /** A whole run against one server. */
  static final class Run {
    final String server, protocol;
    final OrderedMap<String, Object> scenario;
    final Seq<Step> steps = new Seq<>(Step.class);
    String error, serverLog;
    double setupSeconds;
    int readyRooms, failedRooms, failedClients;
    /** Error lines of the server log while running, and while the bench disconnected. */
    Integer serverErrors, serverShutdownErrors;

    Run(String server, String protocol, Scenario sc) {
      this.server = server;
      this.protocol = protocol;
      scenario = sc.toMap();
    }

    /** Best passed step by relayed packets, or {@code null}. */
    Step best() { return steps.select(Step::passed).max(s -> (float)s.relayedPps()); }
    /** Biggest passed step by connections, or {@code null}. */
    Step biggest() { return steps.select(Step::passed).max(s -> s.connections()); }
    /** Most relayed packets, even while failing. */
    Step peak() { return steps.select(s -> s.verdict() != Verdict.BENCH_BOUND).max(s -> (float)s.relayedPps()); }
    Step step(String name) { return steps.find(s -> s.name().equals(name)); }
    long idle() { return Math.round(steps.sumf(s -> (float)(s.idlePps() * s.seconds()))); }
  }

  // end region
  // region formatting

  static String amount(double v) {
    return v >= 1e6 ? String.format("%.2fM", v / 1e6) : v >= 1e4 ? String.format("%.1fk", v / 1e3) :
           v >= 1e3 ? String.format("%.2fk", v / 1e3) : String.valueOf(Math.round(v));
  }
  static String ms(double ms) {
    return String.format(ms >= 100 ? "%.0f" : ms >= 10 ? "%.1f" : "%.2f", ms); // Strings.fixed() truncates
  }
  static String pct(double v) { return String.format("%.1f%%", v); }
  static String cpu(Double v) { return v == null ? "-" : Math.round(v) + "%"; }
  static String secs(double v) { return v == 0 ? "-" : Strings.fixed((float)v, 1) + "s"; }

  private static String or(Step step, Func<Step, String> text) {
    return step == null ? "-" : text.get(step);
  }

  // end region
  // region columns

  /** A step value, shown in the console and/or written in the CSV. */
  record Column(String header, String key, boolean console, Func<Step, Object> value, Func<Step, String> text) {}

  private static Column show(String header, String key, Func<Step, Object> value, Func<Step, String> text) {
    return new Column(header, key, true, value, text);
  }

  private static Column file(String key, Func<Step, Object> value) {
    return new Column(key, key, false, value, null);
  }

  static final Seq<Column> COLUMNS = Seq.with(
    show("step", "step", Step::name, Step::name),
    show("rooms", "rooms", Step::rooms, s -> String.valueOf(s.rooms())),
    show("clients", "clients", Step::clients, s -> String.valueOf(s.clients())),
    show("conns", "connections", Step::connections, s -> String.valueOf(s.connections())),
    show("grow", "grow_seconds", Step::growSeconds, s -> secs(s.growSeconds())),
    file("grow_timeout", Step::growTimeout),
    file("join_failures_pct", Step::joinFailuresPct),
    file("target_pps", Step::target),
    file("window", Step::window),
    file("seconds", Step::seconds),
    show("sent/s", "sent_pps", Step::sentPps, s -> amount(s.sentPps())),
    show("relayed/s", "relayed_pps", Step::relayedPps, s -> amount(s.relayedPps())),
    file("relayed_mbps", Step::relayedMbps),
    show("delivery", "delivery_pct", Step::deliveryPct, s -> pct(s.deliveryPct())),
    show("p50", "p50_ms", Step::p50, s -> ms(s.p50())),
    show("p90", "p90_ms", Step::p90, s -> ms(s.p90())),
    show("p99", "p99_ms", Step::p99, s -> ms(s.p99())),
    show("p99.9", "p999_ms", Step::p999, s -> ms(s.p999())),
    show("max", "max_ms", Step::max, s -> ms(s.max())),
    file("mean_ms", Step::mean),
    show("blocked", "blocked_pct", Step::blockedPct, s -> pct(s.blockedPct())),
    file("lagged_pct", Step::laggedPct),
    file("disconnects", Step::disconnects),
    file("errors", Step::errors),
    file("idle_pps", Step::idlePps),
    file("control_pps", Step::controlPps),
    show("cpu", "server_cpu_pct", Step::serverCpu, s -> cpu(s.serverCpu())),
    file("server_cpu_max_pct", Step::serverCpuMax),
    show("mem", "server_rss_mb", Step::serverRssMb, s -> s.serverRssMb() == null ? "-" : Math.round(s.serverRssMb()) + "MB"),
    show("bench", "bench_busy_pct", Step::benchBusyPct, s -> Math.round(s.benchBusyPct()) + "%"),
    show("verdict", "verdict", Step::verdictText, Step::verdictText)
  );

  // end region
  // region console

  static void printRun(Run r) {
    System.out.println();
    System.out.println("== " + r.server + " - " + r.protocol);
    if (r.error != null) System.out.println("error: " + r.error);
    if (r.steps.isEmpty()) return;

    Seq<Column> cols = COLUMNS.select(Column::console);
    printTable(cols.map(Column::header), r.steps.map(s -> cols.map(c -> c.text().get(s))));

    System.out.println("max sustained: " + or(r.best(), s -> amount(s.relayedPps()) + " relayed/s at " + s.name() +
                       " (p99 " + ms(s.p99()) + " ms)"));
    System.out.println("peak:          " + or(r.peak(), s -> amount(s.relayedPps()) + " relayed/s at " + s.name()));
    if (r.steps.contains(s -> s.connections() != r.steps.first().connections()))
      System.out.println("biggest load:  " + or(r.biggest(), s -> s.rooms() + " rooms x " + s.clients() + " clients (" +
                         s.connections() + " connections) at " + amount(s.relayedPps()) + " relayed/s"));
    if (r.idle() > 0)
      System.out.println("note: the server sent " + r.idle() + " idle notifications to the hosts while measuring");
    if (r.serverErrors != null && r.serverShutdownErrors != null && r.serverErrors + r.serverShutdownErrors > 0)
      System.out.println("server log: " + r.serverErrors + " errors while running, " + r.serverShutdownErrors +
                         " while the bench disconnected");
    if (r.serverLog != null) System.out.println("server log: " + r.serverLog);
  }

  static void printComparison(Seq<Run> runs) {
    if (runs.size < 2) return;
    System.out.println();
    System.out.println("== comparison");
    Seq<String> summary = Seq.with("server", "max sustained", "at step", "conns", "p99", "cpu", "peak", "biggest passed");
    printTable(summary, runs.map(r -> {
      Step best = r.best();
      return Seq.with(
        r.server, or(best, s -> amount(s.relayedPps()) + "/s"), or(best, Step::name),
        or(best, s -> String.valueOf(s.connections())), or(best, s -> ms(s.p99()) + " ms"), or(best, s -> cpu(s.serverCpu())),
        or(r.peak(), s -> amount(s.relayedPps()) + "/s"), or(r.biggest(), s -> s.connections() + " conns")
      );
    }));

    // Steps run by every server, side by side
    Seq<String> common = runs.first().steps.map(Step::name).select(n -> !runs.contains(r -> r.step(n) == null));
    if (common.isEmpty()) return;
    System.out.println();
    Seq<String> head = Seq.with("step").addAll(runs.map(r -> r.server + " (relayed/s, p99, cpu)"));
    printTable(head, common.map(n -> Seq.with(n).addAll(runs.map(r -> or(r.step(n), s ->
      amount(s.relayedPps()) + ", " + ms(s.p99()) + " ms, " + cpu(s.serverCpu()) + (s.passed() ? "" : ", " + s.verdict()))))));
  }

  /** First and last columns are left-aligned, the others right-aligned. */
  static void printTable(Seq<String> head, Seq<Seq<String>> rows) {
    int[] w = new int[head.size];
    for (int i=0; i<w.length; i++) w[i] = head.get(i).length();
    rows.each(r -> {
      for (int i=0; i<w.length; i++) w[i] = Math.max(w[i], r.get(i).length());
    });
    printRow(head, w);
    StringBuilder line = new StringBuilder();
    for (int i=0; i<w.length; i++) line.append(i == 0 ? "" : "  ").append("-".repeat(w[i]));
    System.out.println(line);
    rows.each(r -> printRow(r, w));
  }

  private static void printRow(Seq<String> row, int[] w) {
    StringBuilder b = new StringBuilder();
    for (int i=0, n=row.size; i<n; i++) {
      String cell = row.get(i), pad = " ".repeat(w[i] - cell.length());
      b.append(i == 0 ? "" : "  ").append(i > 0 && i < n - 1 ? pad + cell : cell + pad);
    }
    System.out.println(b.toString().stripTrailing());
  }

  // end region
  // region files

  static void write(Fi dir, Seq<Run> runs, String[] args) {
    OrderedMap<String, Object> json = OrderedMap.of(
      "date", OffsetDateTime.now().toString(),
      "command", Strings.join(" ", args),
      "results", runs
    );
    dir.child("results.json").writeString(JSON.prettyPrint(json));

    StringBuilder csv = new StringBuilder("server,protocol,").append(COLUMNS.toString(",", Column::key)).append('\n');
    runs.each(r -> r.steps.each(s ->
      csv.append(csv(r.server)).append(',').append(csv(r.protocol)).append(',')
         .append(COLUMNS.toString(",", c -> csv(c.value().get(s)))).append('\n')));
    dir.child("results.csv").writeString(csv.toString());

    System.out.println();
    System.out.println("results written to " + dir.absolutePath());
  }

  private static String csv(Object v) {
    if (v == null) return "";
    if (v instanceof Double d) return String.format("%.3f", d); // Strings.fixed() is float only
    String s = v.toString();
    return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? '"' + s.replace("\"", "\"\"") + '"' : s;
  }

  // end region
}
