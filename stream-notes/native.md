# libclaj: a tiny native library for what Java lacks

> Since v8, libclaj is part of `server/`: its C sources in `server/native/`, `LibClaj` in
> `com.xpdustry.claj.server.stream`, built by `:server` (`compileNative`). Paths and commands below are of their round.

Branch `feat/stream-native`, on `feat/stream-udp-mmsg` (`01cb4e1`). The maintainer's words:

> Why not trying a small jni library (as small as possible) for api [not] provided by java. In future i probably
> will make a libclaj thing with a small interface in few languages for it... yea why not, try all these ways to
> see the real change.

The JNI shim of the UDP round (`udp-mmsg`, 53 lines of C) became **libclaj**, the `claj-native` subproject. It is
built for four targets with `zig cc`, and gives the relay what Java doesn't have: batched UDP calls, any int
socket option, and `TCP_INFO`. Raw `epoll` was tried too, and left on its own branch. Each item was measured
against the current build (`01cb4e1`) on a dedicated Boat VM.

**Since v7 (`feat/stream-relay-clean`)**: io_uring left libclaj again (stream-relay.md, "io_uring: assessed and
dropped"), and so did `TCP_INFO` and `getpid`, which nothing in the relay called: `TCP_INFO` was kept "unwired"
below, and `getpid` only served `NativeCallBench`, removed with the JNA and FFM backends. libclaj is now the two
UDP calls, socket options, and their JNI binding are in `claj.c`, a `.so` of **4,408-4,736 B**
per target (zig 0.17.1). The sections below describe the round as it was measured; the removed code is in git at
`11c7e38`.

**In short.**

| Item | Result | Keep? |
|---|---|---|
| 1. C core + JNI glue | one source file containing the plain C calls and JNI glue; `.so` **4,960-5,272 B** per target, 1.7-1.9 KB deflated | **keep** |
| 2. zig cross builds | x86_64 and aarch64, glibc (2.14+ / 2.17+) and musl, all from this x86_64 host; all four load; x86_64 musl passes 19/19 in an Alpine chroot, aarch64 17/19 under qemu-user, both failures qemu's (section 2) | **keep** |
| 3a. `TCP_NOTSENT_LOWAT` | a client that doesn't read leaves **64,407 B → 16,151 B** (16 KiB) or **4,055 B** (4 KiB) in the relay's kernel send queue; no measurable cost on loads A and B | **keep, 16 KiB default** |
| 3b. keepalive on Java 8, random idle | at the default `netdev_max_backlog=1000`, keepalive aborts **922 and 1,257 → 0 and 0** with idle 10-15 s per socket; delivery 68-73% → 86.7% (the pause only) | **keep, 50% spread default** |
| 4. `TCP_INFO` | **737 ns** per call (JDK 25; a bare system call is 357 ns on this VM); gives RTT, unsent bytes, unacknowledged segments, retransmits | keep the call, don't wire it; **removed in v7**, never wired |
| 5. raw `epoll` | the same calls per echo once interest changes are coalesced (4.89 against 4.84 at A 10k/s, 2.28 against 2.24 at C), instructions within 0.5%, CPU within noise; the first version cost +2.31 `epoll_ctl` per echo | **drop** (`wip/stream-native-epoll`) |

## 1. The library

```
claj-native/
  native/claj.c       the C calls and JNI binding, registered from JNI_OnLoad (the only exported symbol)
  src/.../natives/LibClaj.java   the Java 8 side: loads linux-<arch>-<gnu|musl>/libclaj.so from the jar
```

- **A future libclaj in other languages** can use the C functions in `claj.c`; the JNI section passes native
  addresses as `jlong` and returns the C results unchanged.
- **What changed for the relay**: `Mmsg` became `LibClaj` (the same two UDP calls, now `recvmmsg`/`sendmmsg`), and
  `BatchedDatagrams.descriptor` moved to `Sockets`, which also holds the TCP socket options. `JniUdp` forwards to
  `LibClaj`. Nothing else in the relay moved.
- **Loading**: `os.arch` gives the architecture; the C library is musl when the JVM has `ld-musl-<arch>.so.1`
  mapped (`/proc/self/maps`), else glibc. A glibc build can't load on musl (it needs `libc.so.6`) and a musl build
  can't load on glibc (it needs `libc.so`, a linker script there: `invalid ELF header`, tried), hence the two.
  Any failure keeps the Java paths, as before.
- **The startup line names the build**: `... UDP jni, libclaj linux-x86_64-gnu.` (or `unavailable`).

### Sizes per target

| Target | `.so` | in the jar (deflated) | glibc symbol versions needed |
|---|---|---|---|
| `linux-x86_64-gnu` (zig, `x86_64-linux-gnu.2.17`) | 5,272 B | 1,866 B | 2.2.5, 2.12 (`recvmmsg`), 2.14 (`sendmmsg`): **glibc 2.14+** |
| `linux-aarch64-gnu` (zig, `aarch64-linux-gnu.2.17`) | 5,224 B | 1,814 B | 2.17, the first aarch64 glibc |
| `linux-x86_64-musl` (zig, `x86_64-linux-musl`) | 4,960 B | 1,755 B | none (musl) |
| `linux-aarch64-musl` (zig, `aarch64-linux-musl`) | 4,960 B | 1,737 B | none (musl) |
| `linux-x86_64-gnu` (gcc 14, host only, `-Wl,-z,noseparate-code`) | 10,248 B | | 2.2.5, 2.12, 2.14 |

- `JNI_OnLoad` is the only exported symbol of each. `LibClaj.class` is 5,385 B (2,838 B deflated); the
  `claj-native` jar is **12,259 B** with the four libraries (after the review's fixes; 12,236 B before).
- The relay's release jar went from 722,743 B (`01cb4e1`, one gcc `.so`) to **731,935 B** (+9,192 B, before the review's
  fixes): three more libraries, the new calls and `Sockets`.
- zig's linker (lld) lays the file out tighter than GNU ld, hence 5.2 KB against gcc's 10.2 KB for the same code
  (the UDP round's 6,136 B gcc build had two calls, not five). The aarch64 builds are aligned to 64 KiB pages
  (`p_align 0x10000`), so they load on 64 KiB-page kernels too.
- With the epoll calls (the `wip` branch), the libraries were 6.0-6.5 KB.

### Requirements

- Linux 3.0+ (`sendmmsg`), glibc 2.14+ on x86_64 and 2.17+ on aarch64, or any musl; Java 8+.
- On JDK 9+, `--add-opens java.base/sun.nio.ch` for the descriptors (the jar's `Add-Opens` gives it to
  `java -jar`), and `Enable-Native-Access` on JDK 24+ (also in the manifest).
- A `noexec` temporary directory makes the library unavailable (it is extracted, loaded, then deleted).

## 2. Cross builds with zig

**What zig cc is.** Zig is a programming language whose toolchain bundles clang, lld, and the headers and
stub libraries of glibc and musl for many targets. `zig cc` is that toolchain used as a drop-in C compiler:
`zig cc -target aarch64-linux-gnu.2.17` cross compiles for 64-bit ARM and links against glibc 2.17's symbol
versions, from any host, without a sysroot or a cross gcc. One 57 MB download (zig 0.17.1), no root; it is used
this way by e.g. Uber's `hermetic_cc_toolchain` for Bazel.

- **Gradle** (`claj-native`): with `zig` on the PATH, or `-Pzig=/path/to/zig`, it builds the four targets;
  else with gcc for this machine only (`linux-<arch>-<gnu|musl>` by the host's loader); else none, and the relay
  keeps its Java paths. `checkUdpBackends` skips `jni` when there is no library for this machine.
- zig rejects `-Wl,-z,noseparate-code` (GNU ld only); it isn't needed with lld. The other flags are the same:
  `-std=c99 -Wall -Wextra -Werror -Os -fPIC -shared -fvisibility=hidden -s`.
- zig was installed in its own directory (its SHA-256 checked against ziglang.org's index), not in the repo.

### Verified on

| Environment | How | libclaj loaded | Checks (`jni`, 17 datagram checks + keepalive + socket options) |
|---|---|---|---|
| x86_64 glibc (Debian 13 here, Ubuntu 24.04 on the VM) | native | `linux-x86_64-gnu` | all, JDK 25 and Java 8 |
| aarch64 glibc: Ubuntu 24.04 arm64 rootfs, Temurin 25.0.5 | **qemu-user** (`qemu-aarch64-static`, chroot); Boat has no ARM machine | `linux-aarch64-gnu` | 17/19, both failures qemu's (below) |
| x86_64 musl: Alpine 3.24.4, OpenJDK 25.0.5.1 | native, chroot | `linux-x86_64-musl` | **19/19** |
| aarch64 musl: Alpine 3.24.4 aarch64 | qemu-user, chroot | `linux-aarch64-musl` | 17/19, the same two |

These ran the `wip/stream-native-epoll` build, a superset of the final library (the same five calls, plus epoll
and `close`), both with the selector and with raw epoll (`-Dchecks.poller=epoll`, section 5), which also checked
its `epoll_event` conversion on aarch64, where the struct isn't packed. The two failures under qemu-user aren't libclaj's:
- `udp-control-join` fails in the check client's own `Socket.close()` (`NativeThread.signal0`, a `pthread_kill`
  the emulator refuses with `EAGAIN`), and fails the same way with Java's UDP (`-Dchecks.udp=off`), 3/3 runs each.
- `socket-options` gets 4 bytes of `TCP_INFO`: qemu-user's `getsockopt` emulation handles `SOL_TCP` options as
  ints (its `linux-user/syscall.c`).
  Natively, on x86_64 glibc and musl, the kernel writes the whole 248 B struct.

Logs: `v6/native/arch/all.log`, `start.log`.

## 3. `setsockopt` and `getsockopt` of an int

One generic call each, `claj_setsockopt_int(fd, level, name, value)` and `claj_getsockopt_int`, used for two
things. `Sockets.fd(SocketChannel)` reads the channel's descriptor (`SocketChannelImpl.fdVal`), `-1` without
libclaj or `--add-opens`, and `StreamRelay.tune()` sets the options on every accepted socket.

### 3a. `TCP_NOTSENT_LOWAT`

The kernel stops taking writes while more than this many written bytes are still unsent; bytes in flight don't
count. `notSentLowat` (`not-sent-lowat` in `config.json`), in bytes, `0` for the system's
(`net.ipv4.tcp_notsent_lowat`, unlimited by default).

**The review's case** (`review-v5-tcp.md` minor 1): a client with a 1,024 B receive buffer that doesn't read. The
host sends what the credit allows, again as credit comes back, until none comes back for 300 ms. Probe:
`v6/native/probe/LowatProbe.java`, on the VM (`probe-vm.txt`), the same here.

| `notSentLowat` | host sent | credit back | relay's queue (`con.out`) | kernel send queue (`/proc/net/tcp`) | `TCP_INFO` unsent | sent − acked |
|---|---|---|---|---|---|---|
| 0 (today), 64 KiB sent | 65,536 B | 65,536 B | 0 | **64,407 B** | 64,407 B | 576 B |
| 0 (today), 32,767 B sent (the review's) | 32,767 B | 32,767 B | 0 | 31,638 B | 31,638 B | 576 B |
| 4,096 | 65,536 B | 0 | 60,352 B | **4,055 B** | 4,055 B | 576 B |
| 16,384 | 65,536 B | 0 | 48,256 B | **16,151 B** | 16,151 B | 576 B |
| 65,536 | 65,536 B | 65,536 B | 0 | 64,407 B | 64,407 B | 576 B |

- **The bytes don't disappear, they move into the relay's queue**, which the budget already reserves (each stream
  reserves its whole 64 KiB window). What is saved is kernel memory outside the budget: up to 48 KiB per stalled
  stream at 16 KiB, about 47 MB for 1,000 stalled clients.
- **The credit signal becomes what the review asked it to mean**: credit comes back only once the client's queue
  is written, and the kernel holds about the low-water mark of unsent bytes instead of the whole window. It is a
  threshold, not a hard cap: a write can extend a segment past it (the review measured 16,384-18,391 B unsent at
  16 KiB with larger receive buffers), and bytes the client's kernel acknowledged may still be unread by the client.
  The 48 KiB saving is this probe's, a client with a 1 KiB receive buffer.
- The "sent − acked" column is `tcpi_bytes_sent − tcpi_bytes_acked`. It is **not** bytes in flight:
  `bytes_sent` counts retransmissions, and the review found these 576 B are exactly `tcpi_bytes_retrans`, with
  0 unacknowledged segments.
- Every byte still arrives (the probe reads all of them at the end).

**Cost on loads A and B** (series 1, 3 reps each, interleaved; ranges with the mean in brackets; see
"Measurements" for the machine, and why A runs at 10k/s):

| Load | Build | System calls per echo | Split (mean) | Instructions per echo | CPU per echo | p99 | Steal |
|---|---|---|---|---|---|---|---|
| A, 10k/s | base (`01cb4e1`) | 4.75-4.88 (4.83) | writev 2.46, readv 1.00, read 0.81, epoll_wait 0.56 | 84.0-85.2k | 67.9-74.6 µs (71.8) | 1.30-3.74 ms | 0.0-2.2% |
| | libclaj, `notSentLowat` 0 | 4.81-4.86 (4.84) | writev 2.49, readv 1.05, read 0.87, epoll_wait 0.43 | 84.2-84.8k | 64.5-69.8 µs (68.0) | 1.52-3.30 ms | 0.0-0.9% |
| | 4,096 | 4.85-4.89 (4.87) | writev 2.52, readv 1.04, read 0.88, epoll_wait 0.43 | 84.5-85.6k | 64.3-73.5 µs (68.7) | 1.65, 1.94, **103** ms | 0.1-0.2% |
| | 16,384 | 4.82-4.88 (4.85) | writev 2.49, readv 1.06, read 0.88, epoll_wait 0.42 | 84.2-84.9k | 66.5-73.7 µs (70.0) | 1.26-3.62 ms | 0.1-1.3% |
| B | base | 6.17-6.36 (6.29) | writev 2.91, epoll_wait 1.38, readv 1.03, read 0.97 | 97.3-116.3k | 102.1-117.7 µs (107.8) | 0.68-3.10 ms | 0.0-1.3% |
| | libclaj, 0 | 6.13-6.33 (6.25) | writev 2.91, epoll_wait 1.32, readv 1.05, read 0.97 | 99.8-112.6k | 102.7-118.5 µs (110.3) | 0.71-4.93 ms | 0.0-1.7% |
| | 4,096 | 5.92-6.38 (6.22) | writev 2.87, epoll_wait 1.36, readv 1.03, read 0.96 | 95.1-110.1k | 97.2-135.0 µs (111.6) | 0.60-6.21 ms | 0.0-5.6% |
| | 16,384 | 6.16-6.35 (6.28) | writev 2.92, epoll_wait 1.34, readv 1.05, read 0.97 | 96.2-111.3k | 105.0-117.8 µs (109.7) | 0.65-3.42 ms | 0.0-2.1% |

- **No measurable cost.** Calls per echo stay within the base's range at both values, with the same split, and no
  extra `epoll_ctl`: these clients read, so a write never meets the low-water mark. Instructions and CPU per echo
  overlap the base's.
- One run, 4 KiB at A rep 2, had a p99 of 103 ms at 0.2% steal; its other two reps were 1.65 and 1.94 ms. A single
  outlier, not reproduced; 16 KiB never showed it.
- B's 16,000 B messages don't trip 4 KiB either: no `epoll_ctl` shows up in any split, so no write was ever cut
  short into `OP_WRITE`. On loopback the clients take each message at once; a slow real link could show more
  `OP_WRITE` wakeups, not measured here.
- The relay library itself (`notSentLowat` 0 against base) changes nothing, as expected: only accept-time calls.

**Recommendation: keep, 16 KiB, now the default.** Cloudflare sets 16 KB for the same reason, so that what the
server decides to send next isn't stuck behind a long kernel queue
([Optimizing HTTP/2 prioritization with BBR and tcp_notsent_lowat](https://blog.cloudflare.com/http-2-prioritization-with-nginx)).
16 KiB is four of the relay's default 4 KiB slices, so a client that reads always has writes queued. All 60 checks
pass with it on JDK 25 and Java 8. Without libclaj, nothing is set and the behaviour is today's.

### 3b. Keepalive per socket, with a random idle time

**Java 8 first.** The relay sets `TCP_KEEPIDLE`, `TCP_KEEPINTVL` and `TCP_KEEPCNT` on every accepted socket.
Until now that went through `jdk.net.ExtendedSocketOptions` only. That class does have them on Java 8 too, since
**OpenJDK 8u272** (checked: 8u512 lists and sets them), so the gap was older 8u builds and runtimes without it,
which got Linux's 2 hours. `Sockets.keepAlive` now sets them through libclaj first, on every runtime, then through
the JDK's options, then leaves the system's.

**Random idle.** `keepAliveSpread` (`keep-alive-spread`), in percent, default **50**: each socket's idle time is
drawn uniformly between `keepAliveIdle` and 1.5 times it, in whole seconds (the option's unit): 10-15 s by
default. Why 50%:
- **never earlier than configured**, and a dead peer is still found within 15 + 3 × 5 = 30 s instead of 25 s;
- the kernel's timer wheel groups timers 10 s away into buckets of 256-512 ms (the level table of Linux's
  [`kernel/time/timer.c`](https://github.com/torvalds/linux/blob/v6.8/kernel/time/timer.c), levels of
  8× granularity), so the 4,000 clients that joined within 0.5 s fire in one or two softirq runs; six one-second
  buckets cut each burst to about a sixth, under the 1,000-packet loopback backlog in practice (below);
- the idle times differ per socket, so later rounds drift apart rather than staying in step.

**Result** (`stall.sh`, load C, 4 s `SIGSTOP` 8 s into the 30 s step, **`netdev_max_backlog=1000`**, 2 runs each,
interleaved). Keepalive aborts are `TcpExtTCPAbortOnTimeout` from `nstat` over the run:

| Build | Spread | Keepalive aborts | Probes sent (`TCPKeepAlive`) | Delivery | After resuming, sent / relayed |
|---|---|---|---|---|---|
| base (`01cb4e1`) | none | **922, 1,257** | 15,225, 14,931 | 73.3%, 68.2% | 35.4k / 30.8k, 33.7k / 27.4k /s |
| libclaj | 0% | **829, 258** | 15,270, 15,797 | 74.7%, 83.5% | 35.9k / 31.7k, 38.7k / 37.4k /s |
| libclaj | **50% (default)** | **0, 0** | 12,126, 11,962 | 86.7%, 86.7% | 40.0k / 40.0k /s |
| libclaj | 100% | **0, 0** | 10,187, 10,248 | 86.7%, 87.1% | 40.0k / 40.0k /s |

- With the spread, no connection is lost: delivery is the 4 s pause out of 30 s (86.7%), the same as last round
  with the backlog raised to 16,384, and the rate is back to 40.0k/s right after the pause.
- Without it, 258-1,257 aborts per run, in the range of last round's 175-1,691 (another machine). The 0% runs vary a lot
  (829 against 258): how many probes meet a full queue depends on how the join and the timer buckets line up.
- 100% saves more probes (10.2k against 12.1k per run: longer idle times) but finds a dead peer later (up to
  35 s); 50% already removes the aborts.

**Recommendation: keep, 50% by default.** This removes the operational risk of the last round's open question 5
at its loopback form; on real networks it spreads the probes of clients that joined together the same way.
Setting the delays through libclaj also fixes Java 8 builds older than 8u272. The spread works without libclaj
too, wherever the JDK's options exist.

## 4. `TCP_INFO`

`claj_tcp_info(fd, buffer, size)` is `getsockopt(IPPROTO_TCP, TCP_INFO)` into memory the caller owns:
`Sockets.tcpInfo(fd, direct)` fills a 248 B direct buffer (`struct tcp_info` of Linux 6.x; an older kernel writes
less and says so in the returned length). It isn't wired into anything: slow clients are the host's call.

**Cost of one call** (`NativeCallBench`, on the VM, pinned to CPU 3, best of 5 × 2,000,000 calls, on a connected
loopback socket):

| Call | JDK 25.0.5.1 | Java 8u512 |
|---|---|---|
| `getpid()` through JNI, the cost of any system call here | 357.5 ns | 352.2 ns |
| `TCP_INFO`, 248 B | **737.4 ns** | **715.7 ns** |
| `getsockopt` of an int (`TCP_NOTSENT_LOWAT`) | 651.8 ns | 594.2 ns |
| `setsockopt` of an int | 568.3 ns | 543.8 ns |
| for reference: `recvmmsg` on an empty socket (JNI, FFM, JNA), Java's `receive` | 615.0, 616.5, 853.6, 531.6 ns | 575.4, -, 856.0, 582.2 ns |

- System calls are about 5 times dearer on this VM than on last round's (`getpid` 67 ns there): read the costs
  against `getpid`. `TCP_INFO` is about 380 ns more than an empty system call, for copying out 248 B.
- At 4,500 connections, one `TCP_INFO` per socket per second costs about 3.3 ms of CPU per second here, 0.3% of a
  core; per sweep (10 times a second) it would be 3%. A stats line on demand, or for the hosts only, is free.
- `setsockopt` at accept costs 4 calls (keepalive × 3, the low-water mark) ≈ 2.3 µs per connection, nothing
  against the accept and the HELLO exchange.

**What it gives**, per socket, from the kernel's own accounting (offsets in `Sockets`):

| Field | Offset | Unit | What it says, from the probe above |
|---|---|---|---|
| `tcpi_rtt`, `tcpi_rttvar`, `tcpi_min_rtt` | 68, 72, 148 | µs | about 4.5-5 ms: smoothed RTT of the stalled client (up to 4,651 µs in `probe-vm.txt`) |
| `tcpi_notsent_bytes` | 144 | B | 64,407 → 16,151 B with the 16 KiB low-water mark: written, not yet sent |
| `tcpi_bytes_sent`, `tcpi_bytes_acked`, `tcpi_bytes_retrans` | 200, 120, 208 | B | cumulative counters; their difference here, 576 B, is retransmitted bytes, not bytes in flight |
| `tcpi_unacked` | 24 | segments | 0 here: unacknowledged **segments**, not bytes |
| `tcpi_retransmits`, `tcpi_total_retrans` | 2, 100 | count | retransmission timeouts in a row, all retransmitted segments |
| `tcpi_snd_cwnd`, `tcpi_snd_wnd` | 80, 228 | segments, B | congestion window, and the peer's advertised window (not read by the probe) |

**What it could power**, without touching the wire:
- an operator `stats` line per room: the host's RTT, its slowest client (most unsent bytes plus the relay's own
  queue), and retransmissions, sampled once per sweep (100 ms) or on demand;
- a debug log when a client's unsent bytes stay at the low-water mark for seconds, the sign of a stalled reader;
- the bench: real RTTs of the relay's sockets next to its end-to-end latency.
At 4,500 connections, sampling all of them once per second would be 4,500 calls/s: see the cost above.

**Recommendation: keep the call** (it is 7 lines of C and Java), **don't wire it**; an operator command can come
when it's wanted.

## 5. Raw epoll

On its own branch, **`wip/stream-native-epoll`** (2 commits on `89ae2b8` of this branch, +276 lines): `Epoll`
(192 lines with its header), 51 lines in `StreamRelay`, and three epoll calls and `close` in libclaj, behind
`poller = selector | epoll` (default `selector`, and the selector when libclaj can't load).
- Level-triggered `epoll_wait` into a direct `int` array of (slot, events) pairs; a connection is found at
  `slots[data]`, no `SelectionKey`, no selected-key set, no boxed descriptor map.
- The channels stay Java's, non-blocking and unregistered: reads, writes and closes are unchanged. A `Pipe` wakes
  the loop for `close()`. Slots freed during a turn are reused only after it, so an event already returned can't
  reach a newer connection on a reused slot.
- No interest is no registration (`EPOLL_CTL_DEL`), as JDK 11+ does, or a hang-up would be reported forever.
- **Interest changes are coalesced until the next wait**, like the JDK's selector. The first version called
  `epoll_ctl` on every change: **+2.31 `epoll_ctl` per echo** at A10 (6.85 calls per echo against 4.84), because a
  client leaves and rejoins its room's ready queue within a turn. That is the selector's hidden work, and the
  reason why the JDK's steady state has no `epoll_ctl`.

| Load | Build | System calls per echo | Split (mean) | Instructions per echo | CPU per echo | p99 | Steal |
|---|---|---|---|---|---|---|---|
| A, 10k/s | base (`01cb4e1`) | 4.86-4.88 (4.87) | writev 2.45, readv 0.99, read 0.78, epoll_wait 0.64 | 84.9-85.2k | 72.0-75.4 µs (73.4) | 1.23-1.49 ms | 0.0% |
| | selector (`wip`) | 4.81-4.87 (4.84) | writev 2.47, readv 1.00, read 0.83, epoll_wait 0.53 | 84.3-84.8k | 66.8-69.0 µs (68.0) | 1.33-1.71 ms | 0.0-0.1% |
| | **epoll** | 4.84-4.94 (4.89) | writev 2.50, readv 1.01, read 0.84, epoll_wait 0.53 | 83.5-85.0k | 65.9-72.7 µs (69.1) | 1.14-1.55 ms | 0.0-0.1% |
| | epoll, first version | 6.84-6.86 (6.85) | writev 2.36, **epoll_ctl 2.31**, readv 1.16, read 0.82 | 89.9-90.1k | 68.4-72.4 µs (70.6) | 2.34-16.60 ms | 0.1-0.2% |
| C, 20k/s UDP | base | 2.21-2.32 (2.24) | writev 0.87, read 0.86, epoll_wait 0.17, sendmmsg 0.17, recvmmsg 0.17 | 39.0-40.7k | 30.8-34.4 µs (32.2) | 1.04-1.23 ms | 0.1-0.2% |
| | selector | 2.20-2.28 (2.24) | the same | 38.6-40.9k | 29.7-36.5 µs (32.4) | 1.04-1.39 ms | 0.0-0.1% |
| | **epoll** | 2.25-2.35 (2.28) | the same | 39.1-40.2k | 28.9-32.4 µs (31.1) | 0.95-1.14 ms | 0.0-0.1% |

(Series 4. Series 2, with the first version, had base and selector call ranges within 0.1 call, but CPU differs between the series: A10 base 65.9-70.3 µs in series 2, 72.0-75.4 µs in series 4.)

- **Nothing measurable.** The same calls per echo, instructions per echo within 0.5% (84.2k against 84.6k at
  A10, 39.6k against 39.6k at C), CPU per echo inside the selector's spread, p99 alike. The selector's bookkeeping
  (about 1.5% of CPU in the last profiles, and Java 11's `select(Consumer)` already skips the selected-key set)
  is below what these runs can see.
- What it would still remove is the selector's allocation (the boxed fds of its map), so fewer young GCs; not
  measured here, and the instructions say it doesn't cost much.

**Recommendation: drop.** It touches the select loop in a dozen places and adds a second readiness engine to
test, for no measurable gain. The branch stays for the record; if a future profile shows the selector, it is a
rebase away. libclaj's epoll calls left the final branch with it (`f6076cd`).

## Measurements

**Machine.**
- One dedicated Boat VM, `bx_hy96uzda`: `large`, 8 vCPU **AMD EPYC 8224P** (Zen 4c, KVM), 16 GiB, Ubuntu,
  kernel 6.8.0-117, OpenJDK 25.0.5.1. Deleted at the end, and confirmed gone.
- Relay on CPUs 0-1 (`-Xms256m -Xmx2g -XX:ActiveProcessorCount=2`), `claj-bench` on CPUs 2-7 with 6 threads.
  `net.core.netdev_max_backlog=16384` for the load series, `1000` (the default) for the keepalive runs.
- **Not the last round's machine.** Boat placed four VMs on four kinds of host: EPYC 8224P, Ryzen 9 9950X, EPYC
  4585PX and EPYC 4465P; none was last round's EPYC 9455.
  - The first 8224P VM had 0.0-0.1% steal, but at A's 20,000 echoes/s the relay thread saturated (about 46 µs
    of CPU per echo, p99 186-455 ms, every variant alike). Partial data: `v6/native/runs-vm1/`.
  - The Ryzen 9950X and EPYC 4585PX VMs had 2-20% steal under load, which makes CPU per echo meaningless:
    `runs-vm2/`.
  - So every series ran on a second 8224P VM, with steal 0.0-2.2% (one B run at 5.6%), and **load A at 10,000
    echoes/s** (`A10`), where the relay isn't saturated. Numbers are comparable within this round only: at load C
    the base takes 26.3-34.4 µs of CPU per echo here (series 2 and 4), against 12-14 µs (batched) on last round's machine.
- Steal is recorded per run from `/proc/stat` of CPUs 0-1.
- System calls cost 357 ns here (`getpid` through JNI), against 67 ns on last round's machine: what saves calls
  weighs more here, and CPU per echo is higher throughout.

**Method** (`v6/native/measure.sh`, last round's `v5u/measure.sh` with loads A, B and C):
- `compare` with one server, `--pattern echo`, 10 s warmup, 2 s settle, 22 s measured.
- 4 s into the measured step: `perf stat -e task-clock,instructions,raw_syscalls:sys_enter` for 10 s (untraced),
  then `perf trace -s` for 4 s for the split by kind (its shares scaled to the untraced total, an estimate).
- Per echo = count ÷ (offered echoes/s × 10 s). Interleaved: rep × load × build, 3 reps.
- **Loads.** A10: 500 rooms × 8 clients, 256 B, TCP, `--rate 10k`. B: 10 rooms × 4 clients, 16,000 B, TCP,
  `--rate 5k`. C: 500 × 8, 256 B, `--udp 1`, `--rate 20k`.
- **Builds.** `base`: `01cb4e1`, gcc's `.so`. `libclaj`: this branch with zig's (before the 16 KiB default, so
  `notSentLowat` was set per run). `sel` and `epoll`: the `wip/stream-native-epoll` branch, with
  `poller=selector` and `poller=epoll`.
- Every run passed the bench's delivery check (99.8-100%) except where noted.


## Review fixes

An independent review (GPT 6.1 Sol, `review/review-v6-native.md` outside git) found 1 major, 4 minor and 2 nit
issues, each reproduced; all are fixed:
- **A system that refused an option refused every connection.** An old kernel or a seccomp filter answering
  `TCP_NOTSENT_LOWAT` with `ENOPROTOOPT` made admission close every peer (0/20 HELLO with an injected refusal).
  Socket tuning is now optional: the first refusal logs one warning and turns that libclaj option off, and the
  keepalive delays fall back to the runtime's options. The same probe now gives 20/20. Check:
  `socket-tuning-refused`.
- **The spread could draw an idle time Linux refuses** (above 32,767 s), so a valid-looking configuration
  rejected random sockets. `validate()` now checks the longest drawn idle, the interval (≤ 32,767 s) and the probe
  count (≤ 127) at start-up, in long arithmetic. Check: `keepalive-limits`. (Since the v8 review, the fields check
  their own ranges and the drawn idle is capped at 32,767 s instead: see stream-relay.md, "Config validation".)
- **A gcc build after a zig build packaged the old cross-built libraries.** `compileNative` now clears its
  output first and warns when it builds only the host's library or none.
- **Wording**: the low-water mark is a threshold, not a hard cap; "sent − acked" is retransmitted bytes, not
  bytes in flight; the aarch64 summary carries its 17/19; the series comparisons give their actual ranges.
- `claj_tcp_info` retries `EINTR` like the other calls.

## Open questions and not done

(Items 5 and 6 are settled since v7: io_uring was dropped, and so were the FFM and JNA bindings.)

1. **A real ARM machine.** Boat has none, so aarch64 ran under qemu-user only, where `TCP_INFO` and one check
   can't be verified (section 2). GitHub's `ubuntu-24.04-arm` runners would do: `checkUdpBackends` with the jar's
   `linux-aarch64-gnu` library.
2. **CI and releases need zig** to ship the four libraries; without it, Gradle quietly falls back to gcc and the
   jar has only the build machine's (Gradle now warns, and no longer packages libraries left by an earlier zig
   build). Suggested: install zig in the release workflow (e.g. `mlugg/setup-zig`), and
   maybe fail the release task when a target is missing. Not done: no workflow was touched.
3. **The low-water mark on real links** wasn't measured: on loopback a write never meets it. A high-RTT bulk
   stream could take more `OP_WRITE` wakeups: Linux reports a socket writable below half the mark, so about one
   per 8 KiB sent at most, unmeasured.
4. **The machine**: not last round's (section "Measurements"); load A ran at 10k/s. A rerun on an EPYC 9xx5 host
   at A's 20k/s would tie this round to the last.
5. **The io_uring lane** adds its own C file to `udp-mmsg/native/` on its branch from `01cb4e1`. On merge, it
   moves to `claj-native/native/`, and its natives join the table in `claj.c` (one library, one
   `JNI_OnLoad`), or it builds its own library.
6. **The FFM and JNA bindings** only have the two UDP calls; without libclaj (no JNI library for the platform), the
   keepalive delays fall back to `jdk.net`'s options and the low-water mark isn't set.
7. **Not tried**: a glibc build on Alpine with `gcompat`; `TCP_INFO` in an operator command; the selector's
   allocation rate (JFR) against raw epoll.

## Checks

- `./gradlew :stream-relay:checkRelay` passes **60/60** on JDK 25 and Java 8u512 (`-PtestJava=...`), with the
  16 KiB low-water mark as default; `checkUdpBackends` passes 17/17 per backend (off, jni, jna, ffm on JDK 25;
  off, jni, jna on Java 8).
- New checks: `keepalive-options` (24 connections: every delay within 10-15 s, at least three different ones, the
  interval and count exact), `socket-options` (`TCP_NOTSENT_LOWAT` read back, `-EBADF` as `-9`, `TCP_INFO` of an
  established socket with an RTT and acknowledged bytes).
- **20 times in a row** with `-Pudp=jni`, on JDK 25 and Java 8: `keepalive-options`, `socket-options`,
  `datagram-batch`, `datagram-ipv6`, 80/80 each.
- The epoll branch: all 60 checks with `-Ppoller=epoll` on both runtimes, and `close-short-write`,
  `datagram-batch`, `shutdown`, `fd-exhaustion`, `idle-timeout-datagrams` 20 times each, 100/100 per runtime.

## Reproduce

```sh
# zig in its own directory, on the PATH for Gradle: ./gradlew builds the four targets, else gcc for this machine
# These commands are for this round's checkout: git checkout 11c7e38 (NativeCallBench, TCP_INFO and JNA were
# removed in v7; -Ppoller=epoll is on wip/stream-native-epoll).
export PATH=/path/to/zig-x86_64-linux-0.17.1:$PATH
./gradlew :stream-relay:release :bench:release
./gradlew :stream-relay:checkRelay [-PtestJava=...] [-Pudp=jni] [-Ppoller=epoll]   # poller: the wip branch
# VM: as udp-batching.md, then
./measure.sh <name> <jar> <A|A10|B|C> [--set not-sent-lowat=16384] [--set poller=epoll]
sudo sysctl -w net.core.netdev_max_backlog=1000
SETX="--set keep-alive-spread=50" ./stall.sh <jar> <name>          # aborts: nstat's TcpExtTCPAbortOnTimeout
java -cp claj-stream-relay.jar:probe/classes com.xpdustry.claj.stream.LowatProbe 0,4096,16384,65536 65536
taskset -c 3 java ... -cp claj-stream-relay.jar:jna.jar:stream-relay/build/classes/java/test \
  com.xpdustry.claj.stream.NativeCallBench
```

Raw data, outside git: `<raw data>/v6/native/`: `series1.txt` (low-water mark),
`series2.txt` and `series4.txt` (epoll), `series3.txt` and `runs/st.tgz` (keepalive), `micro.txt`,
`probe-vm.txt`, `arch/` (aarch64 and musl), the scripts, and `runs-vm1/`, `runs-vm2/` (the dropped VMs).
