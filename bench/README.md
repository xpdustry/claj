> [!IMPORTANT]
> This sub-project has been vibe coded with Claude Opus 5.5.
>


# CLaJ bench
Load tester for CLaJ servers. <br>
The tool simulates rooms and clients that exchange packets through the relay with configurable gradually increasing load.
And measures the relayed packets per second, latency and CPU. <br>
It supports 2.3.x, and 2.4.x protocols.


## Build
```bash
./gradlew :bench:release
```
The output file will be located at ``build/release/claj-bench.jar``.


## Usage
Start servers locally and compare them on the same scenario:

```bbash
java -jar claj-bench.jar compare \
  --server claj-2.3.3.jar --label 2.3.3 \
  --server claj-server.jar --label 2.4.5-mono --set dual-thread=false
```

`--set` changes a value of the server `config.json`. The per-address limits are disabled, because every bench
connection comes from the same IP.

Benchmark a running server instead. Apply the settings of `claj-bench config` on it first:
```bash
java -jar claj-bench.jar run --host my.server.net --port 7000
```

Every option is listed by `java -jar claj-bench.jar help <command>`.


## Scenarios
`--rooms`, `--clients` (per room) and `--rate` (sends per second) take a value or a ramp `start:step:max`, such as `20:x1.25:100k` or `2:+1:16`. <br>
Each step uses the next value of each ramp.

| Profile | Rooms | Clients | Rate |
|---|---|---|---|
| `throughput` (default) | `100` | `4` | `5k:x1.4:2m` |
| `growth` | `20:x1.25:100k` | `2:x1.2:64` | `4k:x1.5:100m` |

Other useful options:
- `--udp 1`: clients use UDP, like Mindustry.
- `--pattern up+broadcast`: the host broadcasts to its clients (2.4.x only).
- `--pattern echo`: measures the round-trip latency.
- `--flood 16`: sends as fast as possible.
- `--size 32..1024`: random payload sizes in a range (default: `64`).


## Results
A step fails on high latency, lost packets, full sockets, disconnects or join failures. The run stops after 2
failed steps. If the `bench` column goes above 95%, the bench was the bottleneck, and the step is `BENCH_BOUND`.

Results are written to `bench-results/<date>/` (`results.json`, `results.csv`, and the server logs).


## Where to run it
The bench needs as much CPU as the server. On one Linux machine, pin both on separate cores:

```sh
taskset -c 2-7 java -jar claj-bench.jar compare --server-cpus 0-1 --threads 6 ...
```

- **Pterodactyl**: prefer CPU pinning to a CPU limit, as a limit creates fake latency spikes.
- **Two machines**: about 120 bytes per relayed packet and per direction, so the network can limit before the server.
- **Thousands of connections**: raise `ulimit -n` and `net.ipv4.ip_local_port_range`, and prefer Linux to Windows.
