# UDP batching: recvmmsg and sendmmsg in the stream relay

> Since v8, the relay and libclaj are part of `server/` (`com.xpdustry.claj.server.stream`), and batching is
> the `udp-batching` key of the server's `config.json`. Paths and commands below are of their round.

Branch `feat/stream-udp-mmsg`, on `feat/pure-tcp-stream-relay` (`5fed3cc`). Mindustry sends most of its traffic as
datagrams, and every datagram goes through the relay's single shared UDP socket. Java's `DatagramChannel` takes one
system call per datagram, plus an empty receive at the end of each burst. Linux's `recvmmsg` and `sendmmsg` take up
to 32 per call. The maintainer asked to try this with JNI, JNA, "the new 25 proxy method" (FFM) and a small
dedicated project. All three bindings are built and measured here.

**In short.**
- At 20,000 echoes/s over UDP (load C), batching takes the relay from **3.98-4.04 to 2.38-2.59 system calls per
  echo (−38%)** and from **15.6 to 12.9-13.3 µs of CPU per echo (−15 to −17%)**, the same with every binding.
- At 40,000 echoes/s (load D), it goes from 3.67-3.79 to 1.64-1.87 calls per echo (−53%) and the p99 latency halves
  (0.94-1.10 → 0.43-0.58 ms), but the CPU per echo doesn't move beyond the noise.
- JNI and FFM cost the same per call (within 4 ns). JNA costs 36-91 ns more and weighs 2.0 MB.
- **Recommendation: `udpBatching = auto`, which tries JNI, then FFM, then JNA.** Drop JNA, and keep FFM for
  64-bit Linux machines without a bundled `.so` on Java 22+.
- **Since `feat/stream-native`** the JNI shim is libclaj (`claj-native`), cross built with zig for x86_64 and
  aarch64, glibc and musl: see `native.md`. The measurements below are of the shim, which has the same two calls.
- **Since v7 (`feat/stream-relay-clean`) only JNI is left**: the JNA and FFM backends, the `src-ffm` source set,
  the JNA dependency and `NativeCallBench` are removed, as this note recommended for JNA and the orchestrator
  decided for FFM. `udpBatching` takes `off`, `auto` or `jni`. The tables below stay as measured; the removed code
  is in git at `11c7e38`.
- `"udp-batching": "off"` in `config.json` turns batching off.
- The UDP stall anomaly isn't the relay's: it's TCP keepalive on loopback (see "Step 0").

## Step 0: the UDP stall anomaly

**Symptom** (round 4): under load C, after a 4 s `SIGSTOP` of the relay, the relayed rate stayed at about ⅔ of the
sent rate.

**Cause: the bench on loopback, through the kernel. Not the relay's UDP path, and not the stall.**
- Reproduced on the VM: in every stall run but one, the rate stayed low after resuming, with delivery at
  63.0-84.2%.
- The kernel shows no further UDP drops after the pause: the relay socket's drop counter in `/proc/net/udp` and
  `RcvbufErrors` stop moving. So all the datagrams were received.
- A temporary counter build showed where they went: from the resume on, about 6,200 datagrams/s are dropped as
  coming from an **unknown address**. At the same moment, the relay's associations fell from 4,000 to 2,549 and its
  connections from 4,500 to 3,049: **1,451 clients had been closed**.
- A stack trace on close showed why: each closed client's TCP read failed with
  `java.io.IOException: Connection timed out`, which is the kernel's TCP keepalive giving up.
  `nstat` agrees: `TcpExtTCPAbortOnTimeout` 175-1,691 per failing run (1,225-1,691 in the severe ones).
- **Without any SIGSTOP** the same runs fail the same way: delivery 78.7% and 82.2%, with 1,463 and 1,225 aborts.
  The pause doesn't cause it; the run length does.
- **The mechanism.**
  - The bench's UDP clients send nothing over TCP after joining. The relay's keepalive (10 s idle, then 3 probes
    5 s apart) probes the 4,000 idle sockets together, since they all went idle within the 0.5 s of the join.
  - On loopback, every probe and its answer go through the per-CPU input backlog (`net.core.netdev_max_backlog`,
    1,000 packets). Its drop counter in `/proc/net/softnet_stat` grew on the relay's CPUs in every failing run
    (CPU 1: 14,182 → 16,158 → 23,012 over three of them), and not at all with the larger backlog.
  - After 3 lost rounds (about 25 s of TCP silence) the kernel resets the connection. The relay closes the client
    and tells its host. The reset is lost too, so the bench client never notices and keeps sending datagrams from
    an address the relay has forgotten.
- **Confirmed both ways**, with 2 runs each: keepalive off (`--set keep-alive-idle=0`), or
  `netdev_max_backlog=16384`. Then there are 0 aborts and the rate comes back to 40.0k/s right after the pause.
  Delivery is 86.7-87.1%, the 4 s of the pause out of 30 s.

**Fix: none in the relay for this round, but a real operational risk remains.** The 1,000-packet loopback queue
is specific to this setup, and Arc-based clients send a TCP keep-alive every 8 s. But the relay is
game-agnostic and counts datagrams as liveness, so a client may legitimately keep its TCP idle while UDP is busy,
and **datagrams don't reset the kernel's TCP keepalive timer**. Many such clients that went idle together are
probed together, and those probes can still meet a full queue, packet loss or a shared NAT on real networks.
How often that happens in production is unmeasured; it predates batching (see open question 5). The bench README now says to raise
`netdev_max_backlog` for UDP runs longer than about 25 s, and every measurement below ran with 16,384. Two other
options were not done, as they change behaviour for the sake of the bench:
- randomise the keepalive idle time per socket (done since: `native.md` §3b, 0 aborts at the default backlog);
- have the bench's UDP clients send TCP keep-alives.

**On the final build** (`stall.sh`, 4 s `SIGSTOP` 8 s into load C):

| Build | `netdev_max_backlog` | Delivery | After resuming | Keepalive aborts |
|---|---|---|---|---|
| base (`5fed3cc`) | 16,384 | 86.3% | 40.0k/s sent, 40.0k/s relayed | not counted |
| final, `off` | 16,384 | 86.7% | 40.0k/40.0k | 0 |
| final, `jni` | 16,384 | 86.6% | 39.9k/39.7k | 0 |
| final, `ffm` | 16,384 | 86.7% | 40.0k/40.0k | 0 |
| final, `jni` | 1,000 (default) | 63.4% | the keepalive drop again | 1,596 (base: 1,616-1,691) |

Batching doesn't make it worse: with the default backlog, the final build fails like the base, for the same reason.

## Design

| Class | Lines | Role |
|---|---|---|
| `udp-mmsg`: `Mmsg.java`, `native/mmsg.c` | 120, 53 | the JNI shim, its own Gradle subproject (like jansi-native); now `claj-native`, see `native.md` |
| `Datagrams` | 129 | the relay's UDP socket through Java's `DatagramChannel`, as before; picks the backend |
| `BatchedDatagrams` | 256 | `recvmmsg`/`sendmmsg` on the same channel's descriptor |
| `PeerMap` | 144 | connections by UDP address, without an address object per datagram |
| `NativeUdp`, `JniUdp`, `JnaUdp`, `FfmUdp` | 63, 52, 73, 105 | the two calls through each binding, loaded by name |

`StreamRelay` changed by +42/−57 lines, only in the UDP paths:
- the receive loop moved to `Datagrams`, and `datagram()` is the per-datagram part that is left;
- `sendDatagram(payload, address)` became `udp.send(connection, payload)`;
- `udpPeers` became `udp.peers`;
- one call, `udp.flush()`, after `flushDirty()` at the end of the turn.

There is still one selector on one thread, the same wire, and nothing a client or host sees changes.

- **Receive.**
  - `recvmmsg(fd, 32 messages, MSG_DONTWAIT, NULL)` into fixed slots of direct memory, each with its own
    `sockaddr`. It runs at most twice per turn (64 datagrams, the old per-turn limit).
  - When a call returns fewer than 32, the socket is drained, so the trailing empty receive Java needed is gone. The
    selector stays level-triggered, so nothing is stranded.
  - `msg_namelen` is reset before each call. `MSG_TRUNC` makes a datagram count as longer than any valid one, so it
    is dropped by the same checks as with Java's 64 KiB receive.
  - The source address becomes a `PeerMap` key: the IPv6 address in two longs, IPv4 as IPv4-mapped (so both socket
    families give the same key), and the port.
  - An `InetSocketAddress` is only built for an unknown sender whose datagram has the shape of a `HELLO_UDP`. Junk
    from unknown senders allocates nothing.
- **Send.**
  - Datagrams are queued during the turn and sent with one `sendmmsg` at its end, or when 32 are queued, or before
    the receive slots they point into are reused.
  - A host-bound datagram is two iovecs: its `i32 conId`, and the payload where it was received, so it isn't copied.
    A broadcast is one entry per client, all pointing at the same payload.
  - Payloads that live in heap buffers (TCP-host `DGRAM` frames, stage 2 replies) are copied once into a send slot.
  - Destinations are written in the socket's family, learnt from the received addresses.
- **Errors.**
  - A positive result counts how many datagrams from the start of the batch the socket took. Those are never sent
    again, and the call is retried with the rest.
  - `EAGAIN` (socket buffer full) drops the rest of the batch, as Java's send drops a datagram today. Any other
    error drops only the datagram it is about, and the rest goes on.
  - `EINTR` is retried inside each binding. Nothing is queued past the turn.
  - This follows the reference stacks in `research/gemini-check.md` §3 (quiche's `mmsg.rs` prefix progress,
    wireguard-go's unsent-suffix loop), adapted to CLaJ's drop policy.
- **Start-up and fallback.**
  - The descriptor comes from `sun.nio.ch.DatagramChannelImpl.fdVal` by reflection. On JDK 9+ that needs
    `--add-opens java.base/sun.nio.ch`, which the jar's `Add-Opens` manifest entry gives `java -jar`.
  - Both calls are tried once with zero messages, which catches `ENOSYS` (old kernel, seccomp) and a bad
    descriptor.
  - Any failure (no library, wrong OS or architecture, refused reflection, `ENOSYS`, an old Java for FFM) leaves
    Java's UDP: `auto` says nothing, an explicit binding logs one warning. Each case was run: `jna` without
    `jna.jar`, `jni` without `--add-opens`, `ffm` on Java 8. The start-up line names the backend
    (`... memory budget 1024 MiB, UDP jni.`).
- **Config** in this round (`11c7e38`): `udpBatching` with `off`, `auto` (`jni`, then `ffm`, then `jna`), `jni`,
  `jna`, `ffm`. Since v7 only `off`, `auto` and `jni` remain: JNA and FFM were dropped (see `stream-relay.md`).

### The three bindings, and the dedicated library that wasn't

All three call the same two C functions with descriptors laid out in direct memory by `BatchedDatagrams`. Each
returns a count or `-errno`.

| | JNI shim (`udp-mmsg`) | JNA (direct mapping) | FFM (`java.lang.foreign`) |
|---|---|---|---|
| Added bytes | `.so` 6,136 B (1,865 B in the jar), `Mmsg.class` 3,785 B: the `udp-mmsg` jar is 5,062 B; `JniUdp` 1,046 B | `jna.jar` 2,002,589 B (5.17.0; 5.19.1 is 1,950,853 B), next to the relay's jar; `JnaUdp` 1,732 B | none outside the JDK; `FfmUdp` 5,161 B |
| Relay jar | 702,105 → 721,976 B (+19,871 B) for the batching, its three bindings and the `.so` | | |
| Licence | the project's GPL-3.0, our code | LGPL-2.1+ or Apache-2.0 (Apache fits GPL-3.0) | the JDK's |
| Java | 8+ | 8+ | 22+, compiled apart (`src-ffm`, `--release 22`), loaded by name on 22+ only |
| Kernel, libc | Linux 3.0+ (`sendmmsg`; `recvmmsg` 2.6.33), glibc 2.14+ (symbol versions of the `.so`) | Linux 3.0+, any libc with both symbols | the same as JNA |
| Architectures | the `.so`s that are bundled: x86_64 here (now x86_64 and aarch64, glibc and musl, see `native.md`) | every 64-bit Linux JNA ships (x86_64, aarch64, ...) | every 64-bit Linux |
| Build | gcc and JDK headers; Gradle skips the `.so` where they're missing (then unavailable) | none | JDK 22+ to compile `src-ffm` |
| Native access | `Enable-Native-Access: ALL-UNNAMED` in the manifest (JDK 24+ warns without) | same | same |
| errno | `-errno` from C, read right after the call | `Native.getLastError()` | `Linker.Option.captureCallState("errno")` |

- **JNI shim.**
  - One Java 8 class and 53 lines of C. The natives are registered from `JNI_OnLoad`, which is the only exported
    symbol.
  - Built with `-Os -fvisibility=hidden -s -Wl,-z,noseparate-code`. The last flag took the `.so` from 14,328 B to
    6,136 B; the measured jar had the 14,328 B build, with the same code.
  - Extracted from the jar to a temporary file, loaded, then deleted (Linux keeps it mapped). A `noexec` temporary
    directory makes it unavailable, and the relay falls back.
- **aarch64 would take** (done since, with `zig cc`: `native.md`):
  - a second `.so`, from `aarch64-linux-gnu-gcc` with the same flags, or built on an ARM CI runner;
  - a second resource directory (`linux-aarch64/`, which the loader already looks for).
  The Java side needs nothing: the `mmsghdr` layout (64 B, LP64), the errno values (11, 38) and the flags
  (`MSG_DONTWAIT` 0x40, `MSG_TRUNC` 0x20) are the same on aarch64. Until then, `auto` gives FFM on Java 22+ there.
- **An existing dedicated library: none fits** (`research/gemini-check.md` §1):
  - `jetlang/epoll` is receive-only, discards sender addresses (a blocker for an unconnected many-peer socket) and
    has no licence;
  - Netty's `recvmmsg`/`sendmmsg` are package-private behind its transport;
  - JavaCPP's `systems` preset exposes both calls but weighs about 1.69 MiB.
  So variant 4 was skipped.

## Measurements

**Machine.**
- One dedicated Boat VM, `bx_vayne8at`: `large`, 8 vCPU AMD EPYC 9455 (KVM), 16 GiB, kernel 6.8.0-117,
  OpenJDK 25.0.5.1. Deleted at the end, and confirmed gone.
- Relay on CPUs 0-1 (`-Xms256m -Xmx2g -XX:ActiveProcessorCount=2`), `claj-bench` on CPUs 2-7 with 6 threads.
- `net.core.netdev_max_backlog=16384` (Step 0). **Steal 0.0-0.1%** on every run.

**Method.**
- `measure.sh`, the v4 method: `compare` with one server, `--pattern echo`, 10 s warmup, 2 s settle, 22 s measured.
- 4 s into the measured step: `perf stat -e task-clock,instructions,raw_syscalls:sys_enter` for 10 s (untraced),
  then `perf trace -s` for 4 s for the split by kind.
- Per echo = count ÷ (offered echoes/s × 10 s). Interleaved: rep × load × backend, 3 reps. The backend is set with
  `--set udp-batching=<name>`; the server log confirms which one ran.
- Datagrams per call are 1 ÷ calls per echo: each echo is one datagram in (client → relay) and one out
  (relay → client); the host is TCP in the bench. The split by kind is the traced interval's shares scaled to the
  untraced total, an estimate.
- An exact counter in a throwaway build gave the same send occupancy: 2.41 (C) and 4.95 (D) datagrams per
  `sendmmsg`.
- **Load C**: 500 rooms × 8 clients, 256 B, `--udp 1`, `--rate 20k` (20,000 echoes/s). **Load D**: the same at
  `--rate 40k`.
- Every run passed (delivery 99.4-100%).

### Load C, 20,000 echoes/s

| Backend | System calls per echo | Split (mean) | Instructions per echo | CPU per echo | Datagrams per receive call | Datagrams per send call | p99 |
|---|---|---|---|---|---|---|---|
| nio (`off`) | 3.98, 4.04, 4.00 | recvfrom 1.39, sendto 1.06, epoll_wait 0.65, writev 0.47, read 0.43 | 42.0-44.3k | 15.1-16.5 µs (mean 15.6) | 0.71-0.73 (`recvfrom`, empty ones included) | 0.93-0.95 (`sendto`) | 0.18-0.21 ms |
| jni | 2.39-2.59 | epoll_wait 0.65, writev 0.56, read 0.50, **recvmmsg 0.39, sendmmsg 0.37** | 39.8-41.6k | 11.9-13.8 µs (12.9) | 2.46-2.66 | 2.62-2.80 | 0.16-0.19 ms |
| jna | 2.43-2.48 | the same | 42.1-43.1k | 11.6-14.5 µs (13.3) | 2.59-2.68 | 2.69-2.86 | 0.18-0.23 ms |
| ffm | 2.38-2.51 | the same | 40.4-42.5k | 12.4-13.4 µs (13.1) | 2.59-2.71 | 2.55-2.77 | 0.16-0.95 ms |

### Load D, 40,000 echoes/s

| Backend | System calls per echo | Split (mean) | Instructions per echo | CPU per echo | Datagrams per receive call | Datagrams per send call | p99 |
|---|---|---|---|---|---|---|---|
| nio (`off`) | 3.67-3.79 | recvfrom 1.24, sendto 1.14, writev 0.65, read 0.58, epoll_wait 0.11 | 38.8-40.1k | 10.2-11.2 µs (10.7) | 0.79-0.82 | 0.84-0.91 | 0.94-1.10 ms |
| jni | 1.67-1.87 | writev 0.60, read 0.58, epoll_wait 0.22, **recvmmsg 0.18, sendmmsg 0.17** | 35.9-37.0k | 9.3-11.1 µs (10.4) | 4.33-6.70 | 4.95-6.84 | 0.43-0.58 ms |
| jna | 1.64-1.76 | the same | 36.6-37.6k | 11.2-12.2 µs (11.7) | 5.77-8.60 | 5.97-8.60 | 0.44-0.48 ms |
| ffm | 1.69-1.82 | the same | 36.3-36.8k | 9.7-12.2 µs (11.2) | 4.73-6.19 | 5.05-6.23 | 0.44-0.48 ms |

**Reading it.**
- The **system calls** are the reliable measure, as in every round, and they fall as the arithmetic says:
  - about 1.4 receive calls and 1.06 send calls per echo become about 0.39 and 0.37 at C;
  - more datagrams per call at D, so 0.18 and 0.17 there.
  - TCP calls (`writev`/`read` to the TCP hosts) and `epoll_wait` don't change at C.
- **CPU**:
  - At C, every batched run (11.6-14.5 µs) sits below every nio run (15.1-16.5 µs), at 0% steal.
  - At D, the ranges overlap: batching saves calls, but each `sendmmsg` still walks its messages through the UDP
    stack one by one, and at D nio's turns already carry more work per `epoll_wait`.
  - JNA is the slowest binding at D (11.2-12.2 µs), consistent with its per-call cost below.
- **Instructions per echo** fall a little (C: 42-44k → 40-43k; D: 39-40k → 36-38k). The CPU gain at C is larger
  than that: what is saved is the system call entries and exits, which cost more time than instructions on this VM.
- **Latency**: the same at C; the p99 halves at D, since a turn no longer spends one system call per datagram.

### Cost of one call (`UdpCallBench`, now `NativeCallBench`, the same VM, pinned to CPU 3, best of 5 × 2,000,000 calls)

| | `getpid()` | `recvmmsg` of 1 message, empty socket (`EAGAIN`) | Java 8 (8u512): `getpid()`, `recvmmsg` |
|---|---|---|---|
| JNI shim | 67.1 ns | 184.6 ns | 64.7 ns, 182.1 ns |
| JNA direct mapping | 103.5 ns | 275.6 ns | 103.5 ns, 273.8 ns |
| FFM | 68.2 ns | 188.4 ns | not on Java 8 |
| Java's `DatagramChannel.receive`, empty socket | | 155.6 ns (Java 8: 209.0 ns) | |

- **The chat's claims don't hold here.** It said FFM 2-5 ns, JNI 15-30 ns, JNA 25-500+ ns. Measured, JNI and FFM
  are within 1-4 ns of each other, and both are dominated by the system call itself (`getpid` at about 65 ns).
  JNA adds about 36 ns to a call, and 91 ns when it also reads errno. That is still under 0.1 µs, small against the
  3 µs of CPU saved per echo at C, but it is the only binding that is both slower and heavier.
- `Linker.Option.critical` wasn't used, as the research advises for I/O calls.

### GSO and GRO: assessed, not built

UDP GSO would merge sends to **one destination** with **equal sizes** into one message. A temporary counter in
`BatchedDatagrams.queue` counted the sends with the same destination and length as the previous one in the batch:
**0 of 662,658 sends at load C, and 0 of 1,323,415 at load D**.
- Each `sendmmsg` carries 2.4-5 datagrams to different clients: echoes, and in a real room the host's snapshots,
  one per client.
- Host-bound datagrams do share a destination, but a room's clients send a few per second each, so two of them
  rarely meet in one turn's batch. The bench's hosts are TCP, so that path is covered by the checks only, not
  measured.
- GRO likewise needs several datagrams from one peer in one receive. Nothing to gain at these loads, so neither was
  built.

## Recommendation

- **Keep `udpBatching = auto` as the default.** On 64-bit Linux it uses the bundled JNI shim on Java 8+:
  - −38% system calls and −15 to −17% CPU per echo at load C;
  - −53% calls and half the p99 at load D;
  - no change on the wire, the same drop policy, 20 KB more in the jar.
  Elsewhere, or when anything is missing, it is Java's UDP, as before. To switch it off, use `"udp-batching": "off"`
  in `config.json`.
- **Keep JNI as the binding.** It is as fast as FFM, the only one that works on Java 8 without a dependency, and the
  smallest. The cost is a native build per architecture: x86_64 today, and aarch64 is one more gcc call.
- **Keep FFM** (105 lines, no dependency) if machines without a bundled `.so` matter: ARM hosts on Java 22+ get
  batching with no native build. Otherwise drop it too.
- **Drop JNA.** It saves the same calls as JNI but is slower per call, the slowest at load D, and adds a 2.0 MB jar
  to ship next to the relay. Removing it is `JnaUdp.java`, two Gradle lines and the manifest's `Class-Path`.

All three stayed in that branch so the maintainer could compare. v7 kept JNI only: FFM's case was ARM hosts
without a bundled `.so`, and libclaj is now cross built for aarch64 (`native.md`).

## Checks

- `./gradlew :stream-relay:checkRelay` passes 58/58 on JDK 25 (with `auto`, so JNI) and on Java 8u512
  (`-PtestJava=...`), on the VM and here.
- `-Pudp=off|jni` runs every check with that backend, and fails if it isn't the one in use (`jna` and `ffm` until v7).
- `./gradlew :stream-relay:checkUdpBackends [-PtestJava=...]` runs the 17 datagram checks with Java's UDP, then
  with libclaj where it is built. 17/17 each. Until v7 it also ran jna, and ffm on 22+.
- Two new checks:
  - `datagram-ipv6`: a relay on `::1`, UDP host and client, both ways and broadcast;
  - `datagram-batch`: 3 clients × 40 datagrams, more than a turn's 64 and a call's 32, so host sends that point into
    the receive slots are flushed before the slots are reused. Every datagram arrives once, whole and in order,
    the host's 40 broadcasts reach each client in order, and an oversized datagram is dropped.
- Both passed **20 times in a row** with every backend: JDK 25 off, jni, jna and ffm, and Java 8 off, jni and jna
  (40/40 each).

## Open questions and not done

1. **Default**: `auto` (proposed) or `off` until it has run on a real server? It is the maintainer's call. Which
   bindings to keep: proposed JNI (+ FFM), not JNA.
2. **aarch64 `.so`**: built since with `zig cc`, and checked under qemu-user (`native.md`).
3. **Empty datagrams are dropped** on the way from a UDP client to a UDP host, with **every** backend, Java's UDP
   included, so this predates the branch. Found while writing `datagram-batch`; the check doesn't assert it.
   protocol.md says a host's empty `DGRAM` is valid; whether an empty client datagram should reach the host wasn't
   settled.
4. **UDP hosts aren't measured**: the bench's hosts are TCP, so the two-iovec host path and its zero-copy run only
   in the checks. A UDP host option in the bench would measure it.
5. **The keepalive bursts**: an operational risk wherever many clients keep their TCP idle while their UDP is
   busy, since datagrams don't reset TCP keepalive: synchronised probes can bunch up on loopback (measured), and
   possibly on a shared queue, NAT or lossy link (unmeasured). Randomising the keepalive idle per
   socket (e.g. 10-15 s) would spread them, if wanted.
   **Done since** (`native.md` §3b): each socket's idle time is now drawn in 10-15 s (`keepAliveSpread = 50`), and
   the aborts at the default backlog fell from 922-1,257 to 0 per run.
6. **IPv6 scope** isn't part of the peer key: two link-local peers with the same address and port on different
   interfaces would collide. That doesn't happen through a public relay.
7. Not tried: batch sizes other than 32 (quic-go uses 8, Quinn 32, WireGuard 128); `SO_RCVBUF`/`SO_SNDBUF` sizes;
   a quiet single-room latency run.
8. This host's disk filled up during the round (other work; 492 KB free at one point). Builds and checks moved to
   the VM until space came back, and nothing was deleted to make room.

## Reproduce

```sh
./gradlew :stream-relay:release :bench:release      # the relay jar has the .so where gcc is
# VM: sudo sysctl -w net.core.netdev_max_backlog=16384 net.ipv4.ip_local_port_range='1024 65535'; ulimit -n 65536
# jars/: claj-stream-relay.jar, claj-bench.jar
taskset -c 2-7 java -Xmx4g -jar claj-bench.jar compare --server claj-stream-relay.jar --label jni \
  --set udp-batching=jni --server-cpus 0,1 --server-jvm "-Xms256m -Xmx2g -XX:ActiveProcessorCount=2" \
  --rooms 500 --clients 8 --size 256 --rate 20k --udp 1 --pattern echo --threads 6 --warmup 10 --settle 2 --duration 22
#   then on the relay pid: perf stat -x, -e task-clock,instructions,raw_syscalls:sys_enter -p PID -- sleep 10
#                          perf trace -s -p PID -- sleep 4
# the cost of one call: NativeCallBench, removed in v7, in git at 11c7e38 (stream-relay/test)
```

Raw data, outside git: `<raw data>/v5u/` (`series.txt`, `after.txt`, `micro25.txt`,
`micro8.txt`, `results/` with every run's perf, trace and bench logs, the stall logs, `measure.sh`, `stall.sh`, and
`stall-debug.patch`, the temporary counters of Step 0).
