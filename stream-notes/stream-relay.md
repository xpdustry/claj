# The stream relay of the CLaJ server

The relay is a feature of the `server` module, built into its classes (see "v8: inside the server"), with data
rates and fewer settings since v9. It started as a separate module, built on an NIO multi-thread prototype cut
down to one selector, and was cleaned up in v7. The commits these notes name (measured builds, review fixes) are
in its development history, squashed for this branch; that history, the raw benchmark data and the probes
(`<raw data>/` in the paths below) stayed outside the repository, available on request.
Wire protocol: [protocol.md](protocol.md). Code: [`server/src/.../server/stream`](../server/src/com/xpdustry/claj/server/stream).
Java 8 bytecode (jabel, `--release 8`), tested on Java 8 (Temurin 1.8.0_512) and JDK 25.

```sh
./gradlew :server:release                  # build/release/claj-server.jar, libclaj inside with zig (-Pzig=...) or gcc
java -jar claj-server.jar 7000   # TCP and UDP stream relay on 7000
./gradlew :server:checkRelay [-PtestJava=/path/to/java8/bin/java] [-Pchecks=name,name] [-Pudp=off|jni]
./gradlew :server:checkUdpBackends [-PtestJava=...]  # the datagram checks with Java's UDP, then libclaj's
./gradlew :bench:release                   # claj-bench --server claj-server.jar --stream, see Benchmarks
```

## Design

One `Selector`, a TCP listener and a UDP socket on the same port, driven by the server's main thread, which also
runs the console, the timers, the plugins' events and, with `dual-thread` (the default), the 2.x logic: so the rooms
and everything around them have one owner, without locks. No per-frame objects of the relay's own. What allocates
is the JDK: JFR at 500×8/256 B puts 85% of the sampled allocation pressure in the selector (`Integer` boxing in its
fd map, `HashMap` nodes and an iterator for the selected keys), for 1-2 young GCs per minute.
`DatagramChannel.receive` returns an address per datagram.

| Class (`com.xpdustry.claj.server.stream`) | Lines | Role |
|---|---|---|
| `StreamRelay` | 1,424 | the loop: accept, stages, upstream turns, end-of-turn writes, host mux parser, datagrams, budget; the main thread's `Waiter`; the Arc objects sent to a peer as stage 2 replies |
| `StreamControl` | 236 | stage 2 and host `CONTROL` messages, decoded with the `common` packets, decided by `ClajRelay`'s handlers; the budget, pipelining and UDP rules |
| `StreamConnection` | 235 | an `arc.net.Connection`, wrapped by a `ClajConnection`: the socket, rings and credit state of one peer |
| `StreamRoom` | 167 | a `ClajRoom`: its streams by conId, ready queue and mux parser; `OPEN`/`CLOSE` through `ClajRoom`'s hooks, one `CLOSE` per stream on a bulk clear |
| `ByteRing` | 180 | lazy bounded FIFO with scattering reads and gathering writes |
| `StreamProtocol` | 156 | named constants, and the stage 2 type of each `common` reply packet |
| `Datagrams`, `BatchedDatagrams`, `PeerMap` | 118, 240, 150 | the UDP socket: Java's `DatagramChannel`, or `recvmmsg`/`sendmmsg` batches through libclaj on Linux; connections by UDP address. See [udp-batching.md](udp-batching.md) |
| `Sockets`, `LibClaj` | 99, 147 | TCP options beyond Java's through libclaj: keepalive delays with a random idle, `TCP_NOTSENT_LOWAT`. See [native.md](native.md) |
| `server/native/claj.c` | 75 | libclaj and its JNI binding for the calls Java lacks, built by `:server:compileNative` |

In `server/test`: `RelayChecks` (the checks, which boot the server in-process as `Main` does, its 2.x port on the
loopback), and `StreamClient`, `HostMux` (blocking peer helpers: hello and stage 2, the plain `Socket` back; host frames),
`ArcClient` (a 2.x peer of the Arc port, for the checks that mix both transports).

Line counts include the GPL header and Javadoc.

**Backpressure** is mandatory credits, 64 KiB per stream per direction, as the design note asks.
The relay never stops reading a host: every `DATA` byte a host may send is covered by credit, so it fits its
destination (`CONTROL`, `WINDOW` and `DGRAM` frames aren't credited: they are handled at once or sent as a datagram,
never queued for a client). Upstream, the relay never reads a client on its own: a readable client enters its room's
ready queue and, on its turn, `turn()` reads one slice (4 KiB by default, and its credit) **straight into
the host queue**, behind a 7-byte header patched with the real length. A stream alone in the ready queue reads up
to the free host queue instead (20 KiB at most by default): no queued stream waits behind it, though one that is
readable but not queued yet can wait behind those 20 KiB. Turns go on while the host queue holds less
than 16 KiB. So a flooding client waits for its turn like the others, there is no per-client staging
buffer, and each byte is copied once in user space. A client without credit isn't read, except for one byte
of read-ahead that shows an EOF or a reset right behind it.

**Writes** happen at the end of each loop turn: queueing bytes puts the connection in a list, written after the
selected keys are handled, so the frames of a turn usually go out in one `writev` (an aim, not an invariant: a
write can pump or answer more, which queues the connection again in the same flush). `OP_WRITE` is only set on a
socket that didn't take everything, lingering ones included, so the usual write costs no `epoll_ctl` pair.
Credit goes back to a host once half a window (32 KiB) is written, or whenever a client's queue empties.

**Memory.** Rings are allocated on the first queued byte and grow by doubling up to their limit;
stage 1-2 rings are dropped after each reply, and the 130-byte request buffer exists only once a peer sends
something. Host frames are parsed from one shared 64 KiB read buffer: `DATA` payloads go straight into the
client rings, a frame split across reads keeps a 7-byte header and, for small non-`DATA` frames, a lazily
allocated 4 KiB carry buffer. A global budget (default ½ heap) reserves the worst case at admission:
2 KiB per connection (kept for its life), 28.6 KiB per room (40.6 KiB with 16 KiB slices), 64 KiB per stream, plus
large replies while queued.
Refusals say `serverFull`. Kernel socket buffers and the transient old+new arrays while a ring grows are not in
the budget: it bounds the queues, it isn't a proof against every OutOfMemoryError (see open questions).

**Closing.** A connection that closes on purpose lingers: its queued bytes are written, then its output is shut
down and its input discarded until the peer's EOF or the 5 s linger deadline. Closing a socket with unread input
would send a reset, which can drop bytes the kernel hasn't sent yet.

**Errors.** One policy, `failed()`, for errors from a TCP key, an admission, a datagram or the sweep: a
`ProtocolException`, an `IOException`, a `RuntimeException` or an `Error` closes that peer, or its room for a host.
A datagram is matched to its peer before anything can fail, unknown ones are dropped. A plugin's event handler
that throws while a peer leaves can't stop the rest: `ClajRelay` and `ClajRoom` finish the cleanup (registry, room,
clients) in the usual event order, then rethrow, and `StreamRelay.close` logs it. Only a failure of the selector
itself ends the loop.

**What the relay depends on.** The `server` module: `ClajRelay` (rooms, connections, handlers, routines),
`ClajRoom`, `ClajConnection`, `ClajConfig`, `ClajEvents` through them, `ServerApplication`. `arc-core`: `Log`,
`Events`, `LongMap`/`IntMap`/`ObjectIntMap`/`Seq`, `ByteBufferInput`/`Output`. `arcnet`: `Connection` (extended),
`DcReason` (the `CLOSE` reason is its ordinal, as in 2.4.x's `ConnectionClosedPacket`), `Server.ServerConnectFilter`
(`ClajRelay`'s own filter, the blacklist). `common`: the packets, `ClajType`, `RejectReason`, `CloseReason`,
`AddressUtil`. `ClajNet`'s registry is not used for the wire: stage 2 type numbers are the relay's own constants,
mapped from the packet classes in `StreamProtocol`.

**Timeouts.** Four layers, all read by the existing 100 ms sweep, with nothing on the hot path but one stored
timestamp per read or datagram:

| What | Rule | Default, and why |
|---|---|---|
| stage 1, stages 1+2 | absolute deadlines from accept; the stage 1 one is at most the stages 1+2 one | 5 s, 30 s (unchanged) |
| stage 3 idle | a client or host from which nothing was received (TCP bytes, datagrams, a host's `PING`) for `idleTimeout` is closed; a client waiting for credit (queued, or holding its read-ahead byte) is exempt, and bytes kept back count when they are taken | 12 s: Arc's TCP `timeoutMillis` in the 2.x server, which keeps Arc's defaults (`TcpConnection`: keep-alive 8 s, timeout 12 s); its peers send a keep-alive after 8 s without writing |
| host notification | the client's `CLOSE` to the host carries the `DcReason` ordinal: `timeout`, `closed` or `error` (reset, I/O error), like `ClajRoom.disconnected()` → `ConnectionClosedPacket` | — |
| TCP keepalive | `SO_KEEPALIVE` on every accepted socket, and `TCP_KEEPIDLE`/`TCP_KEEPINTERVAL`/`TCP_KEEPCOUNT` from `jdk.net.ExtendedSocketOptions` by reflection when present | 10 s, 5 s, 3 probes: a vanished peer is dropped by the system in about 25 s, even a client exempt from the idle rule. JDK 25 and the tested Java 8u512 both set them; a Java 8 without the options gets only `SO_KEEPALIVE` with the system's delays (2 h on Linux) |

Hosts follow the same idle rule as 2.x hosts do through Arc: a host with nothing to send sends `PING`
(8 s after its last frame keeps Arc's margin). A host timed out closes its room, so its clients are closed too.
`idleTimeout` also settles the half-close question: a client that shut its output down is closed after the window
unless its host closes the stream first.

## Case walk

Checked against the code; test names are in `RelayChecks`, probes in `probes.py` (removed after `11c7e38`, in git
history at `stream-relay/probes/probes.py`). The
"verified by" cells name what each check asserts when it is narrower than the case. `requireClosed()` in the
checks accepts pending bytes and a reset, so a check using it alone proves a close, not exact delivery.

| Case | What happens | Where | Verified by |
|---|---|---|---|
| Client joins an unknown or closed room | `JOIN_DENIED roomNotFound`, stays in stage 2 | `StreamControl.join` | `join-retry` |
| Client before/while the host creates | the room exists only once `ROOM_CREATED` is queued, before that `roomNotFound` | `create`, `startHost` | `join-retry` |
| Retry after a denied join | another request on the same connection; after 5 denials the reply is flushed, then close | `denied`, `linger` | `join-retry`, `join-denied-limit` |
| Wrong password, wrong type, room full | `invalidPassword`/`passwordRequired`, `incompatible`, `roomFull` (also when the room's host queue is over 16 KiB, or conIds run out) | `join` | `join-retry`, `join-incompatible-full` |
| Garbage or partial hello | not `HELLO_TCP` + its token → close; a partial message waits for the hello deadline | `request`, `processRequests` | `hello-garbage`, `hello-slowloris` |
| Slowloris | hello deadline 5 s, then stages 1-2 absolute deadline 30 s from accept, never reset | `sweep` | `hello-slowloris`, `control-deadline` |
| Wrong, foreign or expired token | close over TCP; dropped over UDP; removed from `tokens` with the connection | `request`, `helloTarget`, `close` | `hello-wrong-token`, `hello-expired-token` |
| Duplicate token over UDP | the first datagram associates and spends the token, later ones are dropped | `welcome`, `helloTarget` | `hello-udp-duplicate` |
| UDP token from another address | dropped: the source IP must be the TCP peer's | `helloTarget` | `hello-udp-other-address` |
| Bytes pipelined after a join or a create | refused with `error`, flushed, closed: the relay can't know where the stream starts | `processRequests` (`pipelined`), `join`, `create` | `join-pipelined` |
| Requests over UDP in stage 2 | answered over UDP to the bound address; `JOIN_ACCEPTED`/`ROOM_CREATED` over TCP; list cut to one 4 KiB datagram; unread TCP bytes refuse a join/create; malformed closes; a late `HELLO_UDP` is ignored. Forgeable, see protocol.md | `controlDatagram`, `send`/`queue` | `udp-control-reply`, `-join`, `-create`, `-list-truncated`, `-pipelined`, `-malformed` |
| Request flood | one request at a time, the next waits for the reply to be written; 32 requests max | `processRequests`, `request` | `request-limit` |
| Host disconnects mid-stream | the room closes; every client gets its queued bytes, then EOF, even with unread input (5 s linger) | `close` → `closeRoom` → `linger` → `drain` | `host-leaves`, `close-unread-input` (room teardown), probe `drain … exit` |
| Client disconnects mid-stream | EOF → `FIN` after its last `DATA`; error/reset → `CLOSE` | `turn`, `close` | `client-abort` (FIN or CLOSE, no extra bytes), `half-close-client`; no DATA after CLOSE: probe `race` |
| Client half-close | `FIN` to the host; the host can still send; released after both `FIN`s and a flushed queue | `turn`, `hostFin`, `shutdownOutput` | `half-close-client` |
| Host half-close of a stream (`FIN`) | queued bytes, then `shutdownOutput()`; the client can still send | `hostFin`, `clientWritten` | `half-close-host` |
| Host `shutdownOutput()` on the mux | an EOF: the room closes | `hostRead` | `host-leaves` |
| Host `CLOSE` | the stream leaves the room; queued bytes, then the output shut down and the input discarded until EOF; no frame goes back | `readHeader` (`CLOSE`), `linger`, `drain`, `discard` | `close-after-data`, `close-unread-input` (exact 65,536 B then EOF with 100,000 B unread, also when the host leaves), probe `drain` |
| Slow client (host→client backlog) | at most 64 KiB queued for it; its credit stops; other streams go on; a host that sends past the credit gets it kicked (`CLOSE error`), the room goes on | `readHeader` (`DATA`), `clientWritten`, `flushCredits` | `credit-one-window-queued` (one window, queue ≤ window), `credit-overflow`; sustained: pressure `slow` |
| Slow host (clients→host backlog) | no credit, no read (one byte ahead): bytes wait in the client's kernel buffers; the host queue stays ≤ 16 KiB + a slice | `turn`, `pump`, `readAhead` | `credit-withheld` (no DATA without credit, then exactly the grant); a full host socket: probe `queueflood` (memory only) |
| One flooding client | one slice per turn, FIFO turns: at most one slice ahead of a stream already in the ready queue with it (kernel buffers come on top); alone in the queue, it reads up to the free host queue first (20 KiB by default), ahead of a stream that becomes ready meanwhile | `StreamRoom.offer/poll`, `pump`, `turn` | `credit-turns-both-ready` (both readable before credit); a quiet stream arriving during a flood: pressure `flood` only |
| Host abuses credit | `DATA` over credit → that client kicked, the room goes on (`overrun`); a grant past 64 KiB, a zero grant, bad lengths, `OPEN`, a future conId → room closed, others untouched | `readHeader`, `overrun`, `hostWindow`, `stream` | `credit-abuse`, `credit-overflow`, `broadcast-data`; negative and `MAX_VALUE` grants: review probes |
| conId reuse after close | never: ids increase per room; late frames for a retired id are skipped | `startClient`, `stream`, `readPayload` | `join-incompatible-full`, `close-after-data` |
| Room full / server full | `roomFull`; `serverFull` for the budget, `maxRooms`; pending caps close at accept | `join`, `create`, `admit` | `join-incompatible-full`, `budget-admission`, `connection-caps` |
| Datagrams | UDP host: `i32 conId + payload`; TCP-only host: `DGRAM`, dropped past 16 KiB queued; TCP-only client: dropped | `clientDatagram`, `hostDatagram` | `datagram-udp-host`, `datagram-tcp-host`, bench `dgram*` |
| Broadcast | `DATA`/`DGRAM` with conId 0 to the acknowledged streams, within the smallest credit | `startBroadcast`, `copyBroadcast`, `hostDatagram` | `broadcast-data`, `datagram-udp-host` |
| Server shutdown | `CONTROL ROOM_CLOSED serverClosed` to hosts, one write attempt per connection, close all | `shutdown`, `closeRoom` | `shutdown` |
| Zero-length data | host `DATA` of 0 → violation; `DGRAM` of 0 is a valid empty datagram; a zero-byte client read sends nothing | `readHeader`, `turn` | `credit-abuse` (`empty`) |
| Max frame size | host `DATA` up to 65,535 B, split across reads, into the client ring; the relay sends ≤ one slice | `readPayload`, `turn` | `close-after-data`, `stream-integrity` |
| Exception while handling one peer | the same `failed()` policy for TCP keys, admission and datagrams, `OutOfMemoryError` included: that peer, or its room for a host | `handle`, `admit`, `receive`, `failed` | `error-isolation-tcp-host`, `error-isolation-udp-hello` (fault injection); admission: by reading only |
| Descriptor exhaustion | accept pauses 100 ms, rooms go on; Java 8's lazy socket pair is opened at start | `accept`, `sweep`, constructor | `fd-exhaustion`, probe `emfile` |
| Idle or half-open connection | no buffer once the hello is flushed (released or lazy); a stream that once got bytes keeps its ring until closed | `ByteRing`, `write` | `lazy-buffers`, probes `pending`, `hosts` |
| NAT rebinding of a client's UDP | datagrams from the new port are unknown and dropped; replies go to the old one; TCP unaffected | `receive` | — (open question) |
| Client leaves while it has no credit | one byte of read-ahead: an EOF right behind it is a `FIN`, a reset a `CLOSE` (stream released), no grant needed. A FIN'd stream lives until the host ends it. Not covered: an EOF behind uncredited payload | `readAhead`, `interests`, `turn` | `zero-credit-eof` |

## Review findings

| Finding (review.md) | Status |
|---|---|
| **blocker** nio-mt eager 320 KiB per host, 80 KiB per client | Fixed: lazy rings, no client staging, shared parse buffer. Probes: 1,002 B of heap per idle pending connection and 2,313 B per idle host, JDK socket objects included. |
| major: no global memory budget, OOM kills the process | Fixed: `reserve` at every admission; `OutOfMemoryError` caught per peer. Probes `hosts`, `queueflood` at 128 MiB. |
| major: no per-address admission | Fixed: `maxPendingPerAddress` (stage 1-2), plus `maxConnections`. |
| major: a Throwable in one worker stops every worker | Fixed: one `failed()` policy for TCP keys, admission and datagrams (the v2 review found the UDP and admission gaps). |
| major: speculative multi-worker layer | Deleted: one selector, no `Semaphore`/`ConcurrentHashMap`/migration. |
| major (nio): upstream HOL behind a 512 KiB shared FIFO | Fixed: ready queue + one slice per turn + 16 KiB host queue. Flood p99 0.26-0.31 ms. |
| major (io): DATA after CLOSE; host CLOSE drops queued bytes | Fixed by design: one thread, `close` is the only exit and no frame follows it; host `CLOSE` and host EOF drain. Probes `race`, `drain`. |
| minor: host EOF drops CLOSE-draining clients | Fixed: `closeRoom` lingers every client. |
| minor: client EOF with zero credit waits without deadline | Partly fixed (v2 review): one byte of read-ahead shows an EOF or a reset right behind it. An EOF behind uncredited payload still waits for a grant; a no-progress deadline is an open question. |
| minor: room teardown scans all peers | Fixed: `closeRoom` walks its clients; lingering ones expire in the global 100 ms sweep. |
| minor: `reject()` gives a fresh 5 s | Deadlines are absolute from accept; a refusal past the limit lingers at most 5 s once to flush its reply, and still counts as pending for its address. |
| minor: one-segment ring write | `ByteRing.write` is a gathering write of both segments. |
| minor (nio): future conId silently discarded | Protocol error. |
| minor (nio): close before run races | `start()` and `close()` are synchronized; `close()` before `start()` shuts down in place. |
| minor: room ID is the only credential | Rooms can now have a password (`ROOM_CONFIG` before `ROOM_CREATE`). A per-room join rate is not ported. |
| minor: no idle or partial-frame deadline after the handshake | Idle: `idleTimeout` (12 s) closes a silent client or host. A partial-frame deadline isn't done, open question. |
| style | Named constants (`WINDOW` frame vs `WINDOW_SIZE`), `// region`s, field Javadoc, GPL headers, no one-line blocks. |

### v2 review (review-v2.md)

| Finding | Status |
|---|---|
| major 1: a host `CLOSE` or a room teardown reset a client with unread input: 576 of 65,536 B arrived | Fixed: `linger` → `drain` (shutdown output, discard input until EOF or the linger deadline). `close-unread-input`, 3 variants: 65,536 of 65,536 B then a clean EOF; the review's probe agrees on Java 8 and 25. |
| major 2: a failing UDP hello called `close(null)` and stopped the loop; UDP and admission didn't catch `OutOfMemoryError` | Fixed: the peer is resolved before the guarded work, unknown datagrams dropped, one `failed()` policy. `error-isolation-udp-hello`; the review's probe now finds the loop alive. |
| major 3: stage 2 over UDP is a requirement | Round 3, on the maintainer's direction: implemented without authentication, the forged-source risk annotated (protocol.md, "Stage 2 over UDP"). Six `udp-control-*` checks replace `udp-control-not-implemented`. |
| minor 1: a closed zero-credit client kept its slot and reservation | Partly fixed: one byte of read-ahead (`zero-credit-eof`). A FIN'd stream still waits for the host; an EOF behind uncredited bytes still waits for a grant. Deadline: open question. |
| minor 2: the static join helper leaked its socket when the join threw | Fixed. `join-helper-closes` with a fake relay that withholds the reply. |
| minor 3: test names and "verified by" cells overstated coverage | Renamed `credit-one-window-queued`, `credit-withheld`, `credit-turns-both-ready`, `error-isolation-tcp-host`; cells narrowed. No new tests beyond the fixes. |
| minor 4: benchmark prose | Corrected below: shared ramp step, legacy bench tool, generator-bound boundaries listed, causal claim removed. |
| minor 5: 16 KiB default and the "can't matter" claim | Back to 4 KiB, 16 KiB is a documented setting; the claim is removed. |
| minor 6: the recipe didn't rebuild the variant jars | Recipe below pins every measured build. |
| unconfirmed: a dead client kept as the parser's target | Fixed without a check: `close` clears `frameTarget` and drops the client's ring. |
| style: split the parser and UDP out of `StreamRelay` | Not done (optional, didn't fall out of the fix). |

### v3 review (review-v3.md)

| Finding | Resolution |
|---|---|
| major 1: a short first write while lingering left bytes without `OP_WRITE` | Fixed (`129f424`). `close-short-write`: 1 KiB buffers, 32,768 B DATA then CLOSE, and then a room close; all bytes, then EOF. Failed with 1,728/32,768 B before the fix. |
| major 2: UDP stage 2 ordering | Transitions read TCP first (`1b58da9`, `udp-transition-reads-tcp-first` with the review's selector order); `ROOM_CONFIG` only over TCP, refused over UDP (`udp-control-config`); client rules in protocol.md; TCP-only transitions as open question 16. |
| minor 1: half-window credit | Reverted to every drained queue (`06ef819`); bench host now waits for credit instead of dropping (`d45826b`). |
| minors 2-5: measurement wording, amplification, fairness, hooks | Corrected in both notes. |
| bench: UDP sizes, host buffer, deliveries vs echoes | Stream runs above 4,096 B over UDP are skipped; README and notes disclose the rest. |

### v7 review (review-v7.md, outside git)

| Finding | Resolution |
|---|---|
| major: recovery could escape its peer and stop every room (inherited, not from the cleanup) | A failed turn no longer leaves half a `DATA` frame in the host queue, for any error. A failed `CLOSE` to the host closes that room, and `failed()` falls back to closing the room without notifications. `error-isolation-tcp-client` now checks the host's frames and a failed `CLOSE`; the review's probes, `OutOfMemoryError` included, keep the loop and the other rooms on JDK 25 and Java 8. |
| minor: legacy bench hosts allocated stream-only state | The stream map only for the stream protocol, the staging buffer at the first stall. |
| minor: config aliases broke the file's precedence | `config.json` wins over `-D` whichever key style each uses; check `config-precedence`. |
| minor: benchmark and layout claims too strong | Reworded in the v7 section below. |
| nits: stale counts and instructions, leftovers | Corrected; historical commands name their checkout. |

### v8 review (review-v8.md, outside git)

Each fix has a check that fails on `151e79f` and passes now, on JDK 25 and Java 8; the review's probes show the
fixed behaviour on both.

| Finding | Resolution |
|---|---|
| major 1: Arc ids are random signed ints, so a stream id could replace an Arc connection in the shared registry, and a 2.x host could close a stream room by id | Ids, below. `connection-ids`: the stream id skips a taken Arc one, an Arc host kicking its client leaves the stream room alone, and an Arc connection with a taken id is refused. |
| major 2: queued main-thread tasks starved the stream I/O | A `selectNow` turn between task batches, see the thread decision. `task-fairness`: a byte relayed both ways while tasks repost themselves. |
| major 3: stop/rehost left pending stream peers alive outside the registry | Lifecycle, below. `server-stop`, through `stop` and `host`. |
| major 4: a bulk clear sent stream hosts no `CLOSE` | `StreamRoom.disconnectAllClients`: one `CLOSE` per stream retired when notified, none when quiet; 2.x keeps its broadcast. `bulk-clear`. |
| major 5: a throwing plugin handler in the sweep stopped the relay and left the peer registered | The sweep and the shutdown guard each peer and room; the cleanup goes on past a throwing handler (see Errors above). `plugin-errors`: a handler throwing for an expired hello, and one while a room closes. |
| minor 6: `info`, `warn`, `count`, `uncount`, `rejectRoomJoin(…, roomId)` were made public | Protected again. The stream package uses `uncountStream`, a public `rejectRoomJoin(connection, roomId, reason)` and its own `info`/`warn` (which honour `beQuiet`). The review's `CompatibilityProbe`, and one overriding all five, compile. `protected-api`. |
| minor 7: live config bypassed the start-up validation | Config validation, below. `config-validation`, `keepalive-limits`. |
| minor 8: a `ROOM_STATE` over 1,024 B was truncated | A violation, checked before the `common` reader, which still truncates for 2.x. `oversized-state`. |
| minor 9: `CloseReason` 7-9 undocumented | protocol.md lists every ordinal and where the relay sends it. |
| nit 10: stale line count | Lines, below. |

Disagreement: none on substance. Where a throwing handler's error ends up depends on who called it: under a 2.x
packet, `ServerReceiver` logs it; under a console command, `ClajControl` logs it; in a timer or posted task (the
AFK closure, for either transport), it reaches `Main`'s crash handler; under a stream peer, the stream relay's
`failed()` closes that peer. In every case the cleanup now completes before the error goes on. `ClajConnection`'s constructor stays public:
widening a constructor breaks no subclass.

The re-review (review-v8b.md, outside git) found the same pattern in four more places, all fixed with
`cleanup-errors`: a client removed quietly was left open when `ConnectionLeftEvent` threw; the bulk clear and
`closeRooms` caught `RuntimeException` but not `Error`; `close list` stopped at the first throwing room; a stop
skipped closing the connections, Arc's stop and its callback when a room's close threw. Also: `host-queue-size`
is capped at 512 MiB, so the framing the relay adds can't overflow an `int` and a queue's doubling stays at or
under 2^30.

A narrow re-check (review-v8c.md) added three of the changed lines' own: a bulk clear that threw skipped the empty
room's AFK timer; the stop callback's error replaced the room's (it is now suppressed under it); the 1 GiB cap let
a queue's doubling reach 2^31. Fixed, in `cleanup-errors` and `config-validation`. It also found the same pattern
in 2.x code the relay doesn't change: a throwing handler under `kickClient`, the `reject*` methods,
`ServerStoppingEvent`, `dispose` or a pending room-info rejection still skips the close after it. Those are
upstream's behaviour for both transports; not changed here, to keep the server's diff to the relay's needs (an
open question for the maintainer).

## v7: the cleanup

The maintainer's review of v6: drop io_uring ("if io_uring is making security issues, it is not worth it"), clean
the code to match ZetaMap's syntax, make it less intrusive ("just in the classes in question"), keep it compatible
with the original, and extend `arc.net.Server`/`Connection` if possible.

- **Removed**: io_uring (section "io_uring: assessed and dropped"); the JNA and FFM UDP backends with `NativeUdp`,
  `JniUdp`, `src-ffm` and the JNA dependency (`BatchedDatagrams` calls `LibClaj`); libclaj's unused `TCP_INFO` and
  `getpid`, and `NativeCallBench`; the `stream-nio-mt` notes and data; `probes.py`; the `idle-ready-host` check.
- **Restyled after `server`**: public classes, public state and protected internals as in `ClajConnection` and
  `ClajRoom`, one-line Javadoc where his are, his loop spacing; `StreamClient` and `HostMux` moved to the test
  source set. The wire, the defaults and the checked behaviours are unchanged (`RelayChecks`, 64 checks).
- **Less intrusive**: nothing in `server/`, `common/`, `client/` or `api/` changes; the root `build.gradle` grows
  by 115 lines instead of 167; `gradlew` keeps its upstream mode; the bench keeps its stream protocol support,
  all of it needed to drive the relay.
- **Arc**: not extended, `ServerConnectFilter` reused (section "Extending Arc's `Server` and `Connection`").
- **Added**: `StreamRelay.setConnectFilter` and its `connect-filter` check.
- **Checks**: `checkRelay` (64/64) and `checkUdpBackends` (17/17 with Java's UDP, 17/17 with libclaj), 20 times in
  a row on JDK 25.0.2 and on Java 8u512 (`v7/checks20.txt`, `checks20-java8.txt`; one Java 8 round in the first loop
  lost its Gradle daemon before reporting, so that loop was rerun). The zig 0.17.1 build gives all four targets.

Lines added over upstream `main` (`435117c`), `git diff --shortstat`:

| Module | v6 (`11c7e38`) | v7 |
|---|---|---|
| `stream-relay` main (`src`, `src-ffm`) | 4,351 (19 files) | 2,936 (12 files) |
| `stream-relay` test, probes | 2,038 | 2,119 (`StreamClient` and `HostMux` moved in) |
| `claj-native` | 695 | 249 |
| `stream-notes` | 4,323 (9 files) | 1,805 (4 files) |
| `bench` | +400 −50 | +399 −50 |
| `build.gradle`, `settings.gradle`, `gradlew` | 167 + 1, mode change | 115 + 1 |
| total | +11,975 −51 (48 files) | +7,624 −51 (33 files) |

The relay jar went from 748,995 B to 716,499 B; libclaj from 8,784-9,200 B to 4,408-4,736 B per target.

**Performance, before and after** (VM `bx_wked6qkq`: `large`, 8 vCPU, kernel 6.8.0-117, OpenJDK 25.0.5.1; relay
on CPUs 0-1, bench on 2-7, the v5/v6 method of `measure.sh`; loads A, 500 rooms × 8 clients, 256 B, 20,000
echoes/s over TCP, and C, the same with UDP clients). Base: `11c7e38` with io_uring off (its default); final: the
v7 build (`535113b`); drop: `6a84c41`, whose relay code is that of the io_uring removal alone (the first v7
commit), as a bisection point. Per offered echo; every run passed its step with 100% delivery.

| Series | Load | Build | System calls | Instructions | Relay CPU | p99 | Steal |
|---|---|---|---|---|---|---|---|
| 1, base then final | A | base | 4.38-4.42 | 64.6-65.2k | 20.1-21.3 µs | 1.10-3.23 ms | 0.7-0.9% |
| | A | final | 3.95-4.31 | 64.1-65.3k | 24.2-30.1 µs | 1.74-18.2 ms | 1.0-5.8% |
| | C | base | 2.18-2.22 | 37.8-38.3k | 15.0-16.7 µs | 1.46-3.36 ms | 1.6-2.2% |
| | C | final | 2.35-2.39 | 38.9-39.2k | 13.7-14.5 µs | 0.55-1.20 ms | 0.4-0.8% |
| 2, order rotated | A | base | 4.45-4.52 | 64.9-65.3k | 20.0-21.5 (20.8) µs | 0.65-1.84 ms | 0.2-0.8% |
| | A | drop | 4.42-4.50 | 63.9-65.4k | 19.1-21.0 (19.9) µs | 0.54-0.71 ms | 0.4-0.7% |
| | A | final | 4.38-4.49 | 64.1-64.6k | 18.2-22.1 (20.4) µs | 0.44-1.55 ms | 0.1-1.0% |
| 3, order alternated | C | base | 2.31-2.36 | 38.9-39.1k | 14.3-16.7 (15.1) µs | 0.32-1.17 ms | 0.1-1.2% |
| | C | final | 2.38-2.46 | 39.3-39.8k | 12.8-15.5 (14.4) µs | 0.25-0.44 ms | 0.1-0.3% |
| 4, order rotated | C | base | 2.32-2.45 | 38.8-39.7k | 12.3-15.0 (13.5) µs | 0.19-0.38 ms | 0.1-0.3% |
| | C | drop | 2.25-2.52 | 38.2-40.1k | 11.3-14.9 (12.7) µs | 0.19-0.44 ms | 0.0-0.4% |
| | C | final | 2.29-2.37 | 38.4-39.1k | 12.4-14.7 (13.7) µs | 0.28-0.34 ms | 0.2% |

A first run of series 4 met 3-30% steal (two base runs and one drop run failed their step) and is discarded
(`series4-stolen.txt`).

- **A: no difference.** Series 1 put final 16-41% above base in CPU per echo, 3 of 3 pairs, with the same
  instructions. In series 1 final always ran right after base at A; with the order rotated (series 2) all three
  builds are within each other's spread (means 20.8, 19.9, 20.4 µs): no consistent regression. What caused series
  1's gap (the slot, drift, steal or the JIT) wasn't isolated. `StreamConnection` is 184 B against 192 B
  (Instrumentation, 8-byte aligned); some fields moved (`stage` 80 → 12, `ops` 12 → 72), with no measured effect.
- **C: no difference either.** Series 1 and 3 put final 1-8% above base in system calls (2.35-2.46 against
  2.18-2.36), beyond their spread, with every kind of call up together (the loop turning more often per echo, not
  a new call) and the CPU per echo the same or lower. With the order rotated (series 4), final is at 2.29-2.37,
  overlapping base's 2.32-2.45 and inside drop's 2.25-2.52, and the CPU means are 13.5, 12.7 and 13.7 µs. The base's own
  drift between series (2.18-2.22, 2.31-2.36, 2.32-2.45) is as large as the gap was.
- **Lesson for the method**: interleaving builds in a fixed order is not enough on this VM; the slot after a given
  run is consistently different. Rotate the order within each rep.
- Scripts and raw runs, outside git: `<raw data>/v7/vm/` (`measure.sh`, `series*.sh`, `summary.py`), results in `vm/results/` (`series*.txt`; `runs/` keeps the last run of each name).

## Benchmarks

Rounds 1 to 4 and v7 ran the separate relay jar (`claj-stream-relay.jar`), which the bench recognised by its
`Claj-Protocol: stream` manifest entry, configured with `StreamConfig`'s names; their commands are of their
checkout. Since v8 the bench starts the server jar with `--stream` (see "v8: inside the server").

### Round 4: the low-level round (where the time goes, two experiments)

The maintainer asked for "as much as possible low level things" to cut overhead, allocations and system calls.
The researcher's ranked list (`research/lowlevel.md`, outside git) gave the order. This round measured first,
then tried the two cheapest items: one was kept, one kept in its newer-Java form, and the rest is on hold or
assessed only (see below and the io_uring section).

**Method.** As in round 3 (same `claj-bench` loads, relay on CPUs 0-1, bench on 2-7). Scripts and raw results
are outside git, in `<raw data>/v4/` (step 1) and `v5/` (experiments). On top of round 3:
`perf stat -e instructions` per echo (user and kernel together; it doesn't count stolen time, but steal still
changes how much the loop batches), steal from `/proc/stat` for every run, 3 interleaved reps per build, and for
step 1 temporary counters (`v4/add-counters.py`, never committed) and an async-profiler CPU profile (200 µs
interval). Load **C** is A over UDP: 500 rooms × 8 clients, 256 B, `--udp 1`.

Two caveats from these runs:
- **The traced 4 s fail the step, not the relay.** `perf trace` slows the relay, so the step's p99 at A reads
  300-550 ms on the experiment VM. The per-second samples around it read about 1 ms, and the counts above come
  from the untraced 10 s before. At C the same pause loses datagrams (delivery 85-100%). That is the UDP-pause
  stall the UDP lane investigates (it fails on the base build too), not a change of this round.
- **The experiment VM was about 2× slower per echo than step 1's.** At A the relay used about 88% of a core,
  so the loop turned half as often per echo (0.44 against 0.89) and gathered more per turn. Compare builds only
  inside one table. After about 12:00 the VM's steal rose to 6-21%. Two later rounds (a user/kernel cycle split
  of experiment 1, and A at half rate) ran with the relay pinned at a full core, so they are discarded
  (`v5/b/exp3.txt`, `exp4.txt`).

#### Step 1: where the cost is (VM `bx_t8jhavh8`, steal 0.0%, base `54829e6`)

| Load | Calls/echo | Split | Instructions/echo | CPU µs/echo |
|---|---|---|---|---|
| A | 5.01-5.10 | writev 2.73-2.82, readv 1.06-1.07, read 0.60-0.85, epoll_wait 0.35-0.60 | 79-96k | 22.1-27.3 |
| B | 6.66-6.70 | writev 2.93-2.96, epoll_wait 1.76-1.78, readv 1.00-1.01, read 0.95-0.97 | 115-116k | 41.9-49.1 |
| C | 3.92-3.99 | recvfrom 1.76-1.92, sendto 0.77-0.82, epoll_wait 0.59-0.62, writev 0.33-0.36, read 0.31-0.33 | 43-44k | 14.6-17.4 |

- **Writes.** At A, 1.62 host writes per echo, of which **0.93 carry only `WINDOW` frames** (credit returned by
  a client write after the host had already been written that turn: 0.47 per echo were a second write to the
  same host in one turn). At B, 1.99 host writes, 0.99 `WINDOW`-only. One client write per echo at both.
- **What a write and a read carry** (the maintainer's "send when the buffer is full" question, measured):
  a host write carries 170 B at A and 8,049 B at B; a host read carries 517 B and 3.75 frames at A, 16,380 B and
  2.07 frames at B. The end-of-turn write already gathers what a turn queued. Filling a 16 KiB buffer at A's
  170 B per write would take about 96 turns, and a game waiting for a reply never fills it. So nothing was built
  for it, as the research advised.
- **UDP.** 2.52 datagrams per `receive` burst at C, so batching has something to gather (the UDP lane's work).
- **Profile at A** (samples, inclusive): 80% have a kernel leaf, 69% under the relay's socket writes (on
  loopback a send also runs the receiver's stack), 9% under reads, 6% in `epoll_wait`. The **selected-key set
  is 9.4%** (`HashMap$KeyIterator`, the JDK's `Util$2` wrapper, `HashSet` add and remove), 1.1% at B, 3.1% at C.
  The system call boundary (entry, exit, fd lookup, the JNI stubs) is 2.8% at A and B, 4.1% at C. The JDK
  channel code in user space is 4.6-7.4%, and user-space copies are 0.5% at A and about 4% at B.

#### Experiments (VM `bx_fmbcx82s`, 3 reps each; steal under 0.5% for experiment 1, see `v5/b/exp2.txt` for experiment 2: 0.1-2.8% at B and 0.2-8.0% at C, so its CPU columns are descriptive)

| Experiment | Load | Calls/echo | Instructions/echo | CPU µs/echo | Result |
|---|---|---|---|---|---|
| 1. dirty clients before hosts (`b430288`), against `5fed3cc` | A | 4.65 → **4.19** (4.61-4.68 → 4.12-4.28) | 69.6k → **62.1k** (68.4-70.3 → 60.7-63.5) | 43.9 → 47.9 (42.8-44.9 → 46.6-49.2) | **kept** for calls; CPU +8.9% unresolved, 3 of 3 pairs |
| | B | 6.41 → 6.36 | 103.4k → 97.8k (ranges overlap) | 103.6 → 98.7 | |
| | C | 3.75 → 3.75 | 39.3k → 38.7k | 33.7 → 32.5 | |
| 2. `select(Consumer)` on Java 11+ (`8c2b719`), against experiment 1 | A | 4.23 → 4.29 | 62.7k → 60.5k (59.3-65.1 → 58.8-62.3) | 48.7 → 48.3 | **kept** |
| | B | 6.25 → 6.09 | 96.6k → **88.0k** (91.8-100.6 → 86.0-89.2) | 108.7 → 115.7 (rep 3 at 9-10% steal) | |
| | C | 3.71 → 3.77 | 39.1k → 38.1k | 39.0 → 37.4 | |
| 2a. the 2.x server's `NioUtils` set, against experiment 1 | A / B / C | 4.19 / 6.08 / 3.79 | 60.5k / 100.7k / 38.8k | 47.9 / 123.1 / 37.5 | **rejected** |

- **Experiment 1** writes the turn's dirty clients first and its hosts last. A client write returns credit as
  a `WINDOW` frame to its host, which then rides in the host's one write instead of costing another. Counters
  at A: `WINDOW`-only host writes **0.84 → 0.45 per echo**, host writes 1.58 → 1.25, second writes to a host in
  one turn 0.40 → 0, 175 → 221 B per host write. B: `WINDOW`-only 0.97 → 0.91. It meets the keep rule (fewer
  `WINDOW`-only writes and system calls, every check passing). Not explained: **CPU per echo at A rose 9% in all 3
  reps while instructions fell 11%**. The run meant to split it into user and kernel cycles hit the noisy VM
  (open questions).
- **Experiment 2** resolves `Selector.select(Consumer, long)` once through a `MethodHandle` and uses it when
  present (Java 11+). The JDK then hands each ready key to the loop without the selected-key set. Java 8 keeps the
  iterator. The turn's time is read at the first ready key. Profile share of the key set: **A 1.5% → 0.0%, B
  0.7% → 0.0%** (1.5% rather than step 1's 9.4%, probably because this VM's loop batched twice as much). Instructions per
  echo at B fell 9% with no overlap between reps; A and C stayed within noise; system calls unchanged, as
  expected. It saves more where the loop turns often per echo, which is the lightly loaded case real servers
  are in.
- **Rejected: `NioUtils`** (`server/.../util/NioUtils.java`, moved to `common` for the trial, branch
  `wip/exp2a-nioutils`). It swaps the selector's set for an Arc `ObjectSet`, which still hashes every key: the
  share stayed at 1.7% at A, and instructions didn't drop (B 100.7k). On JDK 25 the fields aren't
  accessible, so it falls back to `sun.misc.Unsafe`, which prints a four-line "terminally deprecated method"
  warning at startup (`objectFieldOffset`, JEP 471/498). `NioUtils` stays in `server`, unchanged.
- **On hold: the "buffer pointer"** (pooled segments instead of the host-to-client copy). User-space copies are
  0.5% of the profile at A and about 4% at B, so the largest change of the round would save at most that.

### Round 3: claj-bench, system calls and CPU per echo

**Tool.** Every number of this round comes from the repo's `bench/` tool, which now speaks the stream protocol
(`Proto.stream()`): stages 1-2 framed like 2.4.x, a client's raw stream carrying the bench's
`u16 length + payload` frames, and the host mux with its credits. A client's first frame is its index in the room,
so the bench host knows which conId is which client. `ServerProcess` starts the relay like a CLaJ server: the jar's
`Claj-Protocol: stream` manifest entry selects the protocol, `--set key=value` goes to `config.json` with the
`StreamConfig` field names (the bench lifts `maxConnections`, `maxPendingPerAddress`, `maxRooms` and raises
`controlTimeout`, as it lifts 2.4.x's limits), and `exit` on stdin stops it. Broadcast flows aren't supported with
the stream relay. The relay's own `Load` generator and `stream-relay/bench/` scripts are deleted; the hostile probes
(`stream-relay/probes/`, since removed) and the checks stay.

**Machine.** One dedicated Boat VM, `bx_5xu7c529`: `large`, 8 vCPU, 16 GiB, kernel 6.8, OpenJDK 25.0.5.0.3. Relay on
CPUs 0-1 (`-Xms256m -Xmx2g -XX:ActiveProcessorCount=2`), bench on CPUs 2-7 with 6 threads. The VM has **CPU steal**:
0-11% of the relay CPUs' time per run, measured from `/proc/stat` since the third experiment. Why steal moves the
relay's task-clock wasn't established (batching, caches and JVM activity all change with it), but **CPU per echo
swings by about ±30% between identical runs**; system call counts move far less (±5%) and are the reliable
measure here. Each build ran 2-4 times, interleaved with its baseline.

**Fixed loads** (`measure.sh`, outside git): `compare` with one server, `--pattern echo`, 10 s warmup, 22 s
measured. 4 s into the measured step, `perf stat -e task-clock,raw_syscalls:sys_enter -p <relay>` counts for
10 s, untraced; then `perf trace -s` for 4 s gives the split by kind (it doubles the relay's CPU, so it only
apportions the untraced total). **Per echo = count / (offered echo rate × 10 s)**: normalised by the offered rate,
not by echoes counted in that window; every run delivered 100% of its step. **The split by kind is an estimate**,
the traced interval's shares scaled to the untraced total.

- **A**: 500 rooms × 8 clients, 256 B, `--rate 20k` (20,000 echoes/s, 40,000 relayed messages/s).
- **B**: 10 rooms × 4 clients, 16,000 B (the bench's limit, set for 2.3.x), `--rate 5k` (5,000 echoes/s = 80 MB/s each way).

System calls per offered echo (runs, then the scaled split of one run) and relay CPU per echo (µs; runs above 4%
steal in italics). Each row is the build named, measured in round 3:

| Build | A syscalls | A split | A CPU µs | B syscalls | B split | B CPU µs |
|---|---|---|---|---|---|---|
| before (`f983c6d`) | 8.04, 8.73, 9.24 | epoll_ctl 3.88, writev 2.47, readv 1.11, read 0.92, epoll_wait 0.35 | 26.1, 24.8, 21.3 | 23.22, 22.85 | epoll_ctl 7.07, writev 4.87, epoll_wait 4.51, readv 4.22, read 2.55 | 51.6, 53.0 |
| 1. end-of-turn writes (`ccef59a`) | 4.99, 5.31 | writev 2.90, readv 1.13, read 0.72, epoll_wait 0.56 | 24.9, 19.5 | 16.31, 17.05, 16.81 | writev 6.11, readv 4.22, epoll_wait 3.96, read 2.75 | 59.5, 44.7, 50.5 |
| 3. lone stream reads more (`d65ac40`) | 5.31, 5.26, *4.63*, 5.02 | writev 2.77, readv 1.10, read 0.75, epoll_wait 0.40 | 17.3, 20.3, *30.0*, 24.9 | 6.79, 6.67, *6.27*, 6.64 | writev 2.99, epoll_wait 1.65, readv 1.02, read 0.97 | 26.3, 40.1, *85.2*, 43.2 |
| 4. credit by half windows (`c0034f7`, **reverted** in `06ef819`) | 4.33, 4.02, *3.51*, 4.17, 4.37 | writev 1.89, readv 1.12, epoll_wait 0.79, read 0.57 | 18.9, 23.7, *33.8*, 18.5, 15.6 | 5.93, 6.02, *5.79*, 5.95, 6.01 | writev 2.34, epoll_wait 1.67, readv 1.02, read 0.98 | 44.8, 37.0, *55.4*, 42.7, 26.1 |

**What the final build was measured as.** Experiment 4 was reverted after the review (it withheld up to 32,767 B of
credit from a quiet stream), so the final build has the credit rule of row 3, and row 3 (`d65ac40`) is the last
measured build with the final steady-state code: **A 8.0-9.2 → 4.6-5.3 system calls per offered echo (about −40%),
B 22.9-23.2 → 6.3-6.8 (about −72%)**. Row 4's lower counts (A 4.0-4.4, B 5.8-6.0) were measured with the
half-window rule and are not the final build's. The final build (`06ef819` and later) also has the review's
fixes (OP_WRITE after a short write while lingering, stage 2 UDP ordering), which don't touch the steady-state
path, and was **not re-measured**. Relay CPU per echo, in these runs: A ~24 → ~20 µs, B ~52 → ~35 µs, within the
noise above. No `epoll_ctl` is left in the steady state; what remains per echo is close to one read and one write
per hop (client read, host write, host read, client write), with several echoes per host read at 500×8.

Left out of the table: one run of experiment 4 from before steal was tracked, with the symptoms of a stolen run
(A: 2.78 calls and 44.0 µs per echo, p99 260 ms, so a failed step; B: 5.01 calls and 148.6 µs). The last two rows
of experiment 4 are `91adaef`, the same relay code plus the port retry.

**Rejected: direct buffers** for the rings, the shared read buffer and the datagram buffer (experiment 2, patch kept
outside git), observed in these runs: same system calls (A 4.15, B 6.0); A 20.4-21.0 µs against 15.6-18.5 for
heap rings at the same low steal; B 32.3-33.9 against 26.1-42.7, which overlap. No system call saved and no reliable
CPU gain, so left out for simplicity. Why A was slower wasn't profiled; it doesn't show that direct memory is
slower in general. The first measurement (A 38.7-41.3 µs, B 101-141 µs) ran before steal was tracked.

**Not tried** (6 experiments allowed, 4 used, 1 re-measured; see open questions): an allocation-free selector by
reflection (the profile shows the selector's garbage, but at 1-2 young GCs per minute a CPU gain would sit inside
the noise), the UDP path, socket options.

### Round 3: final comparison with 2.4.5

Ramp `--rate` steps of ×1.5, one server after the other, each from a fresh process; a step passes with
≥ 98% delivered, p99 ≤ 50 ms and ≤ 1% blocked sends (the bench's defaults). "Max sustained" is the last passing
step in echoes/s, as relayed deliveries ÷ 2 (both echo legs count; it equals completed echoes only when both
legs complete, so near the 98% limit it overstates them slightly); CPU 100% = one core, of the 2 the relay may
use; memory is RSS in MiB (the bench's "MB" column divides KiB by 1,024). Stream
relay at `7553224` (`stream`, before the review fixes, with the half-window credit rule and the bench host that
dropped sends its credit couldn't cover), the round 3 starting point `f983c6d` (`stream-before`), 2.4.5 from this branch's
`server` (`claj-server.jar`, `Claj-Version: 2.4.5`). Steal is the mean (and peak) share of CPUs 0-1 stolen during
that server's ramp, sampled every 5 s; pass 1 started before the sampler.

| Scenario | Pass | stream | stream-before | 2.4.5 |
|---|---|---|---|---|
| TCP 500×8 / 256 B | 1 (2.4.5 first) | 20.3k/s, p99 48.6 ms, 82% CPU, 168 MiB; 30.4k fails at p99 356 ms *(steal not sampled)* | — | **45.6k/s**, 1.30 ms, 164%, 510 MiB *(not sampled)* |
| | 2 (stream first) | **45.6k/s**, 8.58 ms, 67%, steal 9.1% (26%) | **45.6k/s**, 1.62 ms, 91%, steal 2.5% (16%) | 30.4k/s, 18.7 ms, 159%, steal 7.1% (32%); fails its first step at 4k/s (p99 66.6 ms) |
| TCP 10×4 / 16,000 B | 1 | 11.4k/s = 182 MB/s, 22.3 ms, 77%, 101 MiB, steal 6.7% (24%); 17.1k fails at p99 942 ms, 1.7% blocked | **25.6k/s** = 410 MB/s, 4.29 ms, 89%, 177 MiB, steal 6.6% (22%); 38.5k fails at 2.1% blocked | **17.1k/s** = 274 MB/s, 27.9 ms, 129%, 89 MiB, steal 13.0% (34%) |
| | 2 | **25.6k/s** = 410 MB/s, 31.0 ms, 75%, steal 4.8% (16%) | | **25.6k/s** = 410 MB/s, 2.40 ms, 117%, steal 5.5% (22%) |
| UDP 500×8 / 256 B, `--udp 1` | 1 | **30.4k/s**, 10.9 ms, 64%, 186 MiB, steal 8.2% (19%); 45.6k: 89% delivered | — | 6.0k/s, 20.7 ms, 115%, 486 MiB, steal 5.2% (9%); 9k: 65% delivered |

**Reading it.** The ramps move by a whole step between passes for the same jar (stream at 500×8: 20.3k then
45.6k; 2.4.5: 45.6k then 30.4k), and their failures line up with steal peaks, so **these steps don't rank TCP
capacity on this VM**; the per-echo counts above are the round's measurement. Observed in both passes of these
runs, not guaranteed ratios: the stream relay used about half the CPU of 2.4.5 at the same load (500×8 at 20.3k:
52-82% against 134-139%; at 45.6k: 67% against 164%) and about a third of its memory (165-191 MiB against 486-510
MiB at 500×8). The one UDP run (clients over UDP, a TCP host) shows a wide gap: 2.4.5 delivered 65% of the legs at
9k/s, the stream relay 99.2% at 30.4k/s. One run, not a repeatable UDP capacity ratio.

At 10×4 / 16,000 B, pass 1's failing step had 1.7% blocked send attempts (not 1.7% of the echoes): the bench host
then dropped a send its stream's credit couldn't cover. Part of that was the bench (a real host queues: since
`d45826b` the bench does too, so a credit wait shows as latency) and part the relay (experiment 4 withheld up to
32 KiB of credit, reverted in `06ef819`). Under latency a bulk stream stays window-bound, at 64 KiB per round trip.

```sh
./gradlew :stream-relay:release :server:release :bench:release
# bench VM: sudo sysctl -w net.ipv4.ip_local_port_range='1024 65535'; ulimit -n 65536
COMMON='--server-cpus 0,1 --server-jvm=-Xms256m --server-jvm=-Xmx2g --server-jvm=-XX:ActiveProcessorCount=2 --threads 6 --pattern echo -q'
taskset -c 2-7 java -Xmx4g -jar claj-bench.jar compare --server claj-server.jar --label 2.4.5 \
  --server claj-stream-relay.jar --label stream $COMMON --rooms 500 --clients 8 --size 256 --rate 4k:x1.5:300k
#   same with --rooms 10 --clients 4 --size 16000 --rate 1k:x1.5:200k, and with --size 256 --udp 1 at 500x8
# per-echo loads: compare with one --server, --rate 20k (A) or --rooms 10 --clients 4 --size 16000 --rate 5k (B),
#   --warmup 10 --settle 2 --duration 22, then on the relay pid:
sudo perf stat -x, -e task-clock,raw_syscalls:sys_enter -p "$PID" -- sleep 10
sudo perf trace -s -p "$PID" -- sleep 4
```


### Round 1 method (the deleted `Load` generator)

These numbers predate the bench tool and come from the relay's own generator (deleted in round 3, in git at
`d6411ad` under `stream-relay/`); they don't compare directly with the round 3 numbers above.

One dedicated Boat VM, `bx_std5h8ye`: `large`, 8 vCPU, 16 GiB, Ubuntu 24.04, kernel 6.8, OpenJDK 25.0.5.0.3
(the same type and JDK as the unified run, but not the same machine). The relay runs pinned to CPUs 0,1
with `-Xms128m -Xmx2g -XX:ActiveProcessorCount=2`, the generator on CPUs 2–7 with 6 loops. Every point is a
fresh relay and a fresh generator: 3 s warmup, 10 s measured, up to 2 s drain. Ramp, repetitions and verdicts
are the unified ones: offered `4,000 × 1.5^n` echoes/s in total; PASS means zero errors, p99 ≤ 50 ms and
≥ 98% of the offered round trips delivered; a step counts with 2 of 3 passing runs; `BENCH_BOUND` when the
generator uses more than 510% CPU, one loop more than 85%, or misses more than 2% of its send times.
Units: echoes/s = completed round trips; MB/s = decimal payload MB per second, one direction; CPU 100% = one
core; RSS = peak resident memory of the relay process. Medians of 3 runs at the last passing step.

The stream relay's generator is `Load` in `claj-stream-relay.jar`: the unified generator (`9969991`) with
the new handshake and two datagram policies; pacing, measurements and output are unchanged.
`mt-1` (the base, `a04fb44`, one worker) ran again on this VM with the unified generator. The 2.4.5 `baseline`
(main `435117c`) ran with the **legacy bench tool** (`bench --pattern echo --udp 0`) and the unified echo-count
overlay, and the legacy adapter for the isolation cases: same ramp, verdicts and pinning, but not the same generator.
The relay raises `maxPendingPerAddress` to 1,000 for the bench, since every client comes from 127.0.0.1.
Datagram scenarios use the same ramp and verdict; lost datagrams count against the 98% delivery.

Generator-bound boundaries (the next step is `BENCH_BOUND`, so the last passing step isn't relay capacity):
10×4/256 B for `stream-relay` (3/3 runs, generator 356%, a loop at 72%, pacing misses), `stream-relay-flush`
(1/3) and `mt-1` (3/3); 100×4/256 B for `mt-1` (3/3, generator **564.9%**, a loop at 97%). The other
non-passing steps are relay-bound: relay at 98–100% of its one core, generator ≤ 450%, loops ≤ 78%.

Measured builds (relay code; the generator is the same in all of them). `build.sh` makes
`claj-stream-relay.jar` from the checkout it runs in; the other names were copies of that jar built at the
commit given:

| Kind | Jar | Built at | Slice |
|---|---|---|---|
| `stream-relay`, `-dgram`, `-dgram-mux` (matrix, pressure, datagrams, probes) | `claj-stream-relay.jar` | `6ff55c0` | 4 KiB |
| `stream-relay-flush*` (reverted experiment) | `claj-stream-relay-flush.jar` | `a032cd4` | 4 KiB |
| `stream-relay-final`, `-q64`, `-s16` (variants) | `claj-stream-relay-final.jar` | `548c1f9` | 4 KiB, `-q64` adds `-Dstream.hostQueueSize=65536`, `-s16` `-Dstream.sliceSize=16384` |

The current head was not benchmarked: it adds the v2 review fixes (linger drain, read-ahead, error policy) to the
`548c1f9` build. Jar hashes are in the archived `jars/SHA256SUMS`, outside git.

```sh
git checkout 6ff55c0 && stream-relay/bench/build.sh   # the deleted scripts, every jar from committed sources
boat new --type large --name claj-stream-relay-bench --ttl 14400 --no-env --no-snapshots --json
# on the VM: apt-get install openjdk-25-jdk-headless; copy stream-relay/bench and stream-relay/probes
sudo sysctl -w net.ipv4.ip_local_port_range='1024 65535'; ulimit -n 65536
python3 run.py matrix --kinds stream-relay mt-1 baseline
python3 run.py pressure --kinds stream-relay mt-1 baseline
python3 run.py dgram
python3 run.py probes
# variants: build at 548c1f9, copy build/release/claj-stream-relay.jar to jars/claj-stream-relay-final.jar
python3 run.py variants
python3 summarize.py results/raw && python3 tables.py   # results/tables.md
```
For example, the relay and generator of one point:
```sh
taskset -c 0,1 java -Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dstream.maxPendingPerAddress=1000 \
  -jar jars/claj-stream-relay.jar 17010
taskset -c 2-7 java -Xms128m -Xmx4g -XX:ActiveProcessorCount=6 -cp jars/claj-stream-relay.jar \
  com.xpdustry.claj.stream.Main bench 127.0.0.1 17010 500 8 256 11.39 10 both echo 6
```

### Round 1 results

Last passing step of the ramp (median of 3 runs) per scenario: a sampled load step, not a capacity estimate.
**relay 4K** is the first build with 4 KiB slices, the default (full matrix); **relay 16K** the `sliceSize=16384`
setting (bulk sweeps and 100×4/256 B only; 10×4 and 500×8 at 256 B weren't run with it, and TCP coalescing means
the slice can matter even with small chunks). The baseline sends 16,000 B instead of 16,384 B, uses two threads
and its own bench tool.

| Scenario | relay 4K | relay 16K | mt-1 | baseline 2.4.5 |
|---|---|---|---|---|
| 10×4 / 256 B | 518,186/s, p99 2.16 ms, 103 MiB *(next step generator-bound)* | — | 518,317/s, 2.19 ms, 114 MiB *(generator-bound)* | 45,562/s, 0.90 ms, 136% CPU, 95 MiB |
| 10×4 / 16 KiB | 30,370/s = 498 MB/s, 1.29 ms, 115 MiB | **45,550/s = 746 MB/s**, 1.03 ms, 117 MiB (2/3) | **68,332/s = 1,120 MB/s**, 11.49 ms | 30,371/s = 486 MB/s, 1.55 ms |
| 100×4 / 256 B | 518,476/s, 18.01 ms, 147 MiB | 518,268/s, 22.26 ms, 139 MiB | **775,993/s**, 33.46 ms, 176 MiB *(generator-bound)* | 45,560/s, 1.55 ms, 162 MiB |
| 100×4 / 16 KiB | 20,250/s = 332 MB/s, 1.13 ms | **30,375/s = 498 MB/s, 4.50 ms**, 144 MiB | 30,375/s, 16.08 ms, 184 MiB | 30,374/s, 1.65 ms, 227 MiB (2/3) |
| 500×8 / 256 B | 45,562/s, **3.28 ms, 149 MiB** | — | 45,562/s, 39.18 ms, **793 MiB** | 45,561/s, 1.14 ms, 179% CPU, 467 MiB |
| 500×8 / 16 KiB | 13,499/s = 221 MB/s, 5.48 ms, 255 MiB | **20,250/s = 332 MB/s**, 6.49 ms, **223 MiB** | 20,250/s, 9.65 ms, 736 MiB (2/3) | **30,373/s = 486 MB/s**, 21.25 ms, 601 MiB (2/3) |

Every stream relay step above uses one core (relay CPU 64-100%). TCP fds: 51, 501 and 4,501 (listener included)
for every relay. CPU efficiency at the shared 4,000 echoes/s step, echoes per relay CPU percent: relay 16K
240-394, relay 4K 195-446, mt-1 244-355, baseline 38-170.

Isolation (1 room, 3 healthy clients at 100 echoes/s each, 3 runs each):

| Case | relay 4K | relay 16K | mt-1 | baseline |
|---|---|---|---|---|
| never-reader, 256 B / 16 KiB | PASS, p99 0.29 / 0.32 ms, 60 MiB | — | PASS, 0.30 / 0.34 ms | PASS, 0.35 / 0.44 ms, 93 MiB |
| upstream flood, 256 B / 16 KiB | PASS, p99 0.12 / 0.31 ms (flood moved 418 / 8,379 MB) | PASS, 0.11 / 0.26 ms (392 / 13,819 MB) | PASS, 0.11 / 0.26 ms | **FAIL** 1/3 at 256 B (0 healthy echoes median); PASS 0.44 ms at 16 KB |

Datagrams, 100 rooms × 4 clients (relay 4K build; datagrams don't use slices), clients and host over UDP or
a TCP-only host through `DGRAM` frames:

| Host | Size | Last passing | p99 | Lost in 10 s | Next step |
|---|---|---|---|---|---|
| UDP | 256 B | 152,706/s | 1.32 ms | 10,643 (0.7%) | 230,660 offered: 56% delivered |
| UDP | 1,200 B | 102,445/s = 123 MB/s | 0.24 ms | 704 | 153,773 offered: 93% |
| TCP-only | 256 B | 153,713/s | 1.29 ms | 589 | 230,660 offered: 75% |
| TCP-only | 1,200 B | 151,927/s = 182 MB/s | 1.75 ms | 18,438 (2/3 pass) | 230,660 offered: 76% |

Hostile probes (`run.py probes`), fresh relay with `-Xmx128m` (so a 64 MiB budget), relay 4K build. Heap is
live bytes from `GC.class_histogram` while the probe holds its connections; the review's prototypes died in
the first three.

| Probe | Result |
|---|---|
| 3,000 idle pending connections (per-address cap lifted) | alive, +3.0 MB heap = **1,002 B each**, RSS 52 → 64 MiB; fresh host OK |
| same with the default cap (8 per address) | 8 held, 92 closed at accept, fresh host from that address refused, alive |
| 3,000 idle hosts (room cap lifted) | **2,143 created, 857 refused `serverFull` by the budget**, +5.0 MB = **2,313 B per idle host**, RSS 95 MiB, alive |
| 3,000 idle hosts, default `maxRooms` 2,000 | 2,000 created, 1,000 refused `serverFull`, alive |
| 500 rooms, host grants 64 KiB then never reads, client pushes 512 KiB | all 500 opened, +8.1 MB (16 KB per room), RSS 100 MiB, fresh host OK |
| fd limit 160, 400 connects, release | 16 failed accepts logged, then a fresh host OK |
| host sends 240,000 B within credit then CLOSE, slow client (2 KiB buffer) | **240,000 delivered**; same when the host leaves right after |
| client floods then resets, 100 rounds | **0** DATA after the relay's CLOSE, no CLOSE missing |

The 2,143 matches the budget exactly: 64 MiB / (2 KiB + 28.6 KiB). With 16 KiB slices a room reserves 40.6 KiB,
so the same budget would admit about 1,540 idle hosts.

**Tried and reverted in round 1, kept in round 3: writing queued bytes at the end of each loop turn** (commit
`a032cd4`, reverted in `7e39f43`, redone as `ccef59a`). Round 1 judged it on throughput with the old generator:
same 45,562 echoes/s at 500×8/256 B, lower bulk (10×4/16 KiB 20,248 vs 30,370; 100×4/16 KiB 13,499 vs 20,250).
Round 3 judges system calls and CPU per echo, where it cuts 40% of the calls (above); bulk is handled by the lone
stream's larger reads.

**Reading the numbers.** At 500×8/256 B every relay passes the same ramp step, 45,562.5 offered/s
(`4,000 × 1.5^6`), and fails the next one, 68,343.75/s: a shared step, not a tie in capacity. At that step the
stream relay has p99 3.28 ms and 149 MiB, mt-1 39.18 ms and 793 MiB, the baseline 1.14 ms and 467 MiB on
two cores. mt-1 passes a higher step with 256 B chunks at 100×4 (776k vs 518k, its next step generator-bound) and
on 10×4 bulk (1.12 vs 0.75 GB/s with 16 KiB slices). It reads 16 KiB per client and queues up to 256 KiB per
host, where this relay keeps 16 KiB in front of each host; that is a likely cause, not a measured one. The baseline
passes the highest step at 500×8/16 KB, at 2.7× the memory and 3× the p99 of the 16 KiB setting; why was not
measured (it uses two cores, but there was no single-thread control).

**Slice trade-off.** One 4 KiB slice serializes in 32.8 ms at 1 Mbit/s, a 16 KiB one in 131.1 ms (+98.3 ms).
The relay's queue bound in front of a quiet stream (16 KiB host queue + one slice) goes from 163.8 ms to 262.1 ms
at 1 Mbit/s. This is the relay→host direction; a host may send `DATA` frames up to 65,535 B the other way.
Hence 4 KiB by default, 16 KiB as a setting for bulk.

## Rate limits and blacklists

Since v8, the server's own, through its classes:

| Where | What applies | How |
|---|---|---|
| admission (`admit()`) | `blacklist`, `max-connections`, `connection-limit` (both transports together) | `ClajRelay`'s connect filter and the relay's budget, then `ClajRelay.refusal()`, the checks of `allowConnected` |
| stage 2 request (`request()`) | `client-rate` (a request is a packet, at least 1 KB: kicked past it), then each handler's: `join-limit`, `info-limit`, `list-limit`, `room-limit` (rate and count), `type-blacklist`, `max-rooms`, `client-limit` | `ClajRelay.checkRateLimit()`, `onRoomJoin`, `onInfoRequest`, `onRoomCreate`; the list request checks `allowList()` itself |
| host `ROOM_STATE` | `state-limit` | `ClajRelay.onRoomState()` |
| rooms | `afk-time`, `close`/`state` commands | `ClajRelay`'s timers and console, as for 2.x rooms |

| stage 3 reads and datagrams (`turn()`, `hostRead()`, `datagram()`) | `client-rate`, `host-rate` | `ByteRate` of the peer's `ClajConnection`: out of allowance, the peer isn't read until the sweep refills it, its datagrams are dropped |

## v9: data rates and fewer settings

The maintainer: replace `spam-limit` and `host-spam-limit` by a network rate, "e.g. 10mb/s for an host and 2mb/s
for a client" (megabytes), find a ratio per client for the host, slow down rather than kick, and merge the settings
that look alike.

**Rates.** `client-rate` and `host-rate`, in MB/s (1 MB = 1,000,000 B), a token bucket (`server/util/ByteRate`)
with a burst of 3 s, the window `spam-limit` counted over. A host gets `host-rate` for each client of its room and
itself, as `host-spam-limit` was multiplied by `clients + 1`.
- Stream relay: slowed. Out of allowance, a client's turn or a host's read is skipped and its `OP_READ` dropped
  until the 100 ms sweep refills it; the bytes wait in the kernel and TCP pushes back on the sender. Its datagrams
  are dropped meanwhile. A slowed peer doesn't count as idle. Cost: a refill (a few arithmetic operations) per read
  call, never per byte or frame. `data-rate` checks a client at 0.1 MB/s and a host at 0.1 MB/s per member: 4.7 s
  for 1.5 MB in all, against 4.5 s in theory past the bursts; without the throttle, 7 ms.
- 2.x: Arc reads every ready socket itself, and the key it would need to pause is private and re-armed by its own
  writes, so past the rate a client is kicked and a room closed, as with the spam limits. A packet costs its
  payload, at least 1 KB (`MIN_PACKET_COST`), so a flood of small packets is limited too: 2 MB/s is about 2,000
  packets/s, where `spam-limit` allowed 85.
- **The ratio, from Mindustry's sources** (`Anuken/Mindustry`, `NetClient.sync`, `NetServer.sync`; estimated, not
  measured): a client sends a `clientSnapshot` every 66 ms, about 100 B plus its build plans, so 1.5-20 KB/s. Its
  host sends it entity snapshots every 200 ms (`snapshotInterval`) in chunks of up to 800 B, as many as the units
  need, a state snapshot, a block sync every 6 s, and the world on join. A busy game is about 20-200 KB/s per
  client: about 10 times what the client sends. The default caps are per member and equal, 2 MB/s, so they leave
  a host 10 times and a client 100 times its steady traffic, for the bursts (the world on join, a pasted
  schematic). A room of 4 clients gets the maintainer's 10 MB/s.

**Merged settings**, 18 of the relay's down to 10:

| Before | Now | Why |
|---|---|---|
| `spam-limit`, `host-spam-limit` | `client-rate`, `host-rate` | the maintainer's request, above |
| `hello-timeout` (5,000 ms) | `register-timeout` (5 s, 2.x's) | the same role as Arc's registration |
| `max-pending-per-address` (8) | `connection-limit` (64, 2.x's) | it already counts every connection of an address |
| `max-denied` (5) | `max-requests` (32) | a denied request is a request; `join-limit` per address bounds the guessing |
| `keep-alive-idle`, `-interval`, `-count`, `-spread` | `keep-alive` (10 s) | interval half of it, 3 probes and 50% spread are fixed: what was measured |
| `host-queue-size` (16 KiB) | 4 × `slice-size` | the same 16 KiB by default; it was at least one slice anyway |
| `not-sent-lowat` (16 KiB) | fixed, 16 KiB | the value recommended by the native round |
| timeouts in ms | in s, as 2.x's | `control-timeout`, `linger-timeout`, `idle-timeout` take decimals |

Batching is UDP only: `recvmmsg`/`sendmmsg` take many datagrams of one socket per call. Linux has no call for many
TCP sockets except io_uring, dropped. TCP is already one `writev` per socket per loop turn, all its queued frames
in one call.

Older sections keep the names of their time.

## v8: inside the server

The maintainer: "why your not reusing existing classes from server/ ? modify directly the server/ classes to add
your changes, reuse the classes and use as much as possible arc api". So the `stream-relay` and `claj-native`
modules are gone: the relay is a feature of `server`, in `com.xpdustry.claj.server.stream`, started by `Main` next
to `ClajRelay`, which supplies the shared control plane without opening another listener.

### What reuses what

| Before (v7) | Now | Notes |
|---|---|---|
| `StreamConfig` + `-Dstream.*` | `ClajConfig` fields, same kebab-case keys | autosave, `config` console command and `config reload` for free; each field checks its values (see "Config validation"). `max-connections`, `max-rooms`, `connection-limit` and `client-limit` apply to the relay. `slice-size`, `host-queue-size`, `memory-budget` and `udp-batching` are read at start |
| `StreamRoom` | `StreamRoom extends ClajRoom` | in `ClajRelay.rooms` and `types`; membership, events, AFK, `close` commands are `ClajRoom`'s and `ClajRelay`'s. The mux, ready queue and streams by conId stay, through two new `ClajRoom` hooks (`notifyJoined`, `notifyLeft`) and its existing `setRoom`/`removeRoom` |
| `StreamConnection` | `StreamConnection extends arc.net.Connection`, wrapped by a `ClajConnection` | in `ClajRelay.connections` from accept until closed; ids are unique across both transports (see "Ids") |
| `StreamControl`'s decisions | `ClajRelay`'s handlers | `onRoomCreate`, `onRoomJoin` (as a join request, then `addClient`), `onInfoRequest`, `onRoomConfig`, `onRoomState`, `onConClose`, `closeRoom`; `StreamControl` keeps what only the stream has: budget, pipelining, UDP, the room list in one message |
| own replies | `ClajRoom`'s and `ClajRelay`'s packets | `StreamConnection.sendTCP(Object)` sends a `common` packet as its stage 2 message (`StreamProtocol.typeOf`), or `ROOM_CLOSED` in `CONTROL` to a host; others (text, popups, state requests) have no stream wire and are dropped |
| `Main`, the relay thread | `Main`, `ServerApplication` | see the thread decision below |
| `setConnectFilter` | `ClajRelay`'s filter | the blacklist of the 2.x port refuses stream connections too |
| logging | the server's formatter, `ClajRelay`'s log lines | the 2.x log lines (room created, joined, left, closed) appear for stream rooms; the relay's own go through `StreamRelay.info`/`warn`, which honour `beQuiet` as `ClajRelay`'s protected ones do |
| room ids | `ClajRelay.newRoomId()` | one id space; a 2.x client can't see or join a stream room and the other way round (`ClajRelay.getRoom(connection, id)`) |
| `claj-native` | `server/native`, `:server:compileNative` | `claj.c` registers `com/xpdustry/claj/server/stream/LibClaj` |

Changes in `server` outside the new package, additive for 2.x (methods only added, none widened):
`ClajConfig` (17 fields for the relay, and `bind-address`; `Field.check`/`range`, which the 2.x fields don't use),
`ServerApplication` (the `Waiter`, its turn between task batches), `util/TaskQueue` (`run(max)`), `ClajRoom`
(`notifyJoined`/`notifyLeft`, `isStream`, `containsClient` by connection, cleanup past a throwing handler),
`ClajConnection` (`isStream`, public constructor), `ClajRelay` (`refusal()` out of `allowConnected`,
transport-aware `getRoom`, `newRoom` for stream hosts, a stream peer not closed after a denial, `uncountStream` and
`rejectRoomJoin(connection, roomId, reason)` for the stream package, the id check in `onConnect`, the stream peers
closed on stop, cleanup past a throwing handler, `bind-address`), `ClajControl` (`rooms` marks stream rooms),
`ClajStateSummary` (counts stream connections), `ClajVars.stream`, `Main` (starts the relay). The jar manifest
gains `Add-Opens: java.base/sun.nio.ch` and `Enable-Native-Access: ALL-UNNAMED`, for libclaj.

For 2.x alone, the visible differences are in failure cases only: an Arc connection drawing an id that a
stream peer holds is refused (a chance of n in 2^32 per accept with n stream peers; the client reconnects), and a throwing plugin handler no longer
leaves a room, a client or a stop half done.

Behaviours that follow the server now, where v7 had its own:
- **Order of refusals** of a room creation: `ClajRelay.onRoomCreate`'s (closed, room count, version, type
  blacklist, rate), so a full server answers `serverFull` before checking the version (`room-create-refused`).
- **`ROOM_INFO`'s `maxClients`** is the room's own limit, 0 for the server's, as 2.x's `RoomInfoPacket`; v7 sent the
  effective one.
- **Defaults**: `client-limit` 128 (v7: 64), `max-connections` 1,048,576 (10,000), `max-rooms` 65,536 (2,000); the
  memory budget still bounds them. Per-address rates are on by default, as for 2.x: a bench or a NAT must lift them.
- **Budget**: a room reserves 26.0 KiB (v7: 28.6 KiB), a stream 64 KiB + 42 B (v7: 64 KiB): the frames of a stream
  in its host queue are reserved per stream, since `client-limit` can be 0 (unlimited), instead of per room for the
  room's maximum.
- **Events**: `ClientConnectedEvent` at accept, `ClientDisonnectedEvent` before the room it leaves, as
  `ClajRelay.onDisconnect` orders them (`plugin-events`).
- **Errors**: any `Error` while handling a peer closes that peer, no longer only `OutOfMemoryError`, since plugin and
  event code now runs inside the loop.

### The thread decision

The relay's state and the rooms must have one owner. The options, assessed in about 20 minutes:

1. **The stream loop on its own thread, posting to the thread that owns the rooms.** Nothing crosses threads on the
   hot path, but every stage 2 decision, join, leave and room close does, twice. The rules that are simple because
   one thread sees everything become asynchronous: lockstep replies, the pipelining check, "transitions read TCP
   first" over UDP, a room closing while a join is in flight. Room membership would live on one thread and the
   streams on the other. Several hundred lines of hand-off code, each path asking "what changed meanwhile".
2. **One thread driving both selectors** (`Server.update(0)` and the stream select): two selectors in one thread
   need zero timeouts, so a busy loop or added latency. And with `dual-thread` (the default), `ClajRelay`'s logic
   doesn't even run on its network thread: it is posted to the main thread.
3. **The stream channels on Arc's selector**: `Server.update()` casts every key's attachment to `Connection` and
   reads objects from it (`Server.java:188-346`): its whole loop would be overridden.
4. **The main thread drives the stream selector while it waits for tasks** (chosen). In the default configuration
   the main thread already owns the rooms: 2.x packets are decoded there, the timers (AFK, state and list
   timeouts) and the console run there. `ServerApplication` waits for tasks with a `Waiter` instead of its monitor:
   `StreamRelay.await()` runs loop turns until a task is posted (`post()` calls `selector.wakeup()`) or the main
   loop's timeout. No hand-off at all: `StreamControl` calls `ClajRelay` synchronously, as v7 called its own
   decisions. On the hot path, one more read per turn (`hasTasks()`, an atomic counter).

   Neither side starves the other (v8 review): while tasks are queued, the main loop gives the relay one
   `poll()` turn (`selectNow`, so each ready key once, the sweep and the writes) before each batch, and runs at
   most 1,024 tasks per batch (`TaskQueue.run(max)`; without a waiter, the batch is unbounded as before). Idle,
   the relay blocks in `select` until a task's `wakeup()`, as before.

Costs of 4, accepted: blocking work on the main thread stalls the stream relay, as it already stalls the 2.x logic
with `dual-thread`: the `status` command's disk walk, autosaves, a slow plugin handler. And under heavy 2.x load the
two share one core. With `dual-thread` off, `ClajRelay` changes the rooms from its network thread, which would race:
the server then refuses to start the relay, with an error.

### Ids

Arc draws each connection id at random over the whole `int` range (`Server.generateId`), checking only its own
connections, and keeps it private. The stream relay takes the next id, going down from -1, that no connection in
`ClajRelay.connections` has (`StreamRelay.nextId`), and skips 0 (`CON_BROADCAST`). An Arc id drawn later can still
meet a live stream id: `ClajRelay.onConnect` then refuses that Arc connection (uncounted, detached from its
`ClajConnection` so its queued events are ignored, closed with an error, a warning). So every registered id is
unique, and the 2.x wire ids are unchanged. Host actions also check the object: `ClajRoom.containsClient` compares
the connection, and `onDisconnect` only removes its own registry entry.

### Lifecycle

`ClajRelay.stop` (and `host`, which stops first) closes the rooms, as before, then closes the stream relay's
connections too (Arc's `stop` closes only its own), gracefully: they stay in the registry until closed. While the
server is closed, the stream listener stays bound but `refusal()` turns every new connection away, as Arc's port
does; after `host`, both accept again. `StreamRelay.close`, the stream relay's own shutdown, is unchanged.

### Config validation

Each of the relay's `ClajConfig` fields declares its valid values (`Field.range`/`check`): `set` and `decode`
refuse others (the console prints "Invalid value for setting 'keep-alive-spread': must be from 0 to 1000."), and
`reload` keeps the current value, with an error, if the file holds an invalid one (the default if it is the first
load). So a live change can't break admission, and no cross-field rule is left to check at start:
`StreamRelay.validate` is gone. What it enforced across fields now holds by construction: the drawn keepalive
idle is capped at Linux's 32,767 s, the host queue is at least one slice, and the stage 1 deadline is at most
`control-timeout`.

### The Arc decision

- **`Connection`: extended.** `StreamConnection extends arc.net.Connection` so that `ClajConnection` wraps it like any
  other: `getID()`, `isConnected()`, `sendTCP`/`sendUDP(Object)` (the stage 2 replies), `close(DcReason)` (a graceful
  close: queued bytes, then EOF), `getRemoteAddressTCP`/`UDP`, `getTcpWriteBufferSize`, `isIdle`, and no-ops for the
  timeouts the relay has its own of; `sendTCPBuffer`/`sendUDPBuffer` throw. `initialize` isn't called, so there is no
  `TcpConnection`: about 75 B more per connection (v7's measure of a bare subclass), plus the `ClajConnection`
  wrapper and its `Ratekeeper`. Arc's `NetListener`s on the connection are never called: the events are
  `ClajEvents`, which plugins use. Nothing changes on the hot path: the relay reads its own fields, as before.
- **`Server`: not extended.** Its selector and accept path are private (`Server.java:23`, `:372`), `update()` is the
  whole loop and reads objects through the `NetSerializer`, and `sendToAll*` would serialize objects into raw
  streams: a subclass would replace every public method or get them wrong. What fits is reused: `ClajRelay`'s
  `ServerConnectFilter`, `DcReason`, and `ClajRelay` itself for everything above the wire.

### Checks

`RelayChecks` boots the server in-process as `Main` does (`ClajConfig` loaded, `ClajRelay` hosted on the loopback,
`ClajControl` registered) and starts each check's relay on the main thread; `fd-exhaustion` starts the real server
from jars in another process. 67 checks: v7's 64, minus `config-precedence` and `config-keys` (no more `-D`
overrides), plus `config-clajconfig` (the keys, the console's `config` command, save and reload, validation),
`console-rooms` (`rooms`, `rooms info`, `status`, `close room <id> afk`), `routines` (address and type blacklists, the
join rate as "not found", the packet spam limit) and `plugin-events` (the `ClajEvents` of a stream room's life, in
order). `checkRelay` 67/67 and `checkUdpOff` 17/17, 20 rounds each on JDK 25.0.2 and Java 8u512, at `7391bf6`
(`<raw data>/v8/loops/`). An earlier set of rounds found `host-leaves` failing 2 times in 80: the check
read the rooms from its own thread, see `7391bf6`. libclaj builds for the four targets with zig 0.17.1, 4,424-4,736 B
each, as in v7.

After the v8 review: 76 checks, the 67 above plus `connection-ids`, `task-fairness`, `server-stop`, `bulk-clear`,
`plugin-errors`, `protected-api`, `config-validation` and `oversized-state` (each fails on `151e79f`, JDK 25 and
Java 8), `cleanup-errors` after the re-review (a throwing `ConnectionLeftEvent` or `RoomClosedEvent`, `Error`
included, during a quiet removal, a bulk clear, `close list` and a stop: each case fails without its fix), and `config-clajconfig`, `keepalive-limits`, `socket-tuning-refused` adapted to the field checks.
`checkRelay` 75/75, 20 rounds each on JDK 25.0.2 and Java 8u512, at the fixes' last code commit
(`<raw data>/v8/fixes/loops/`; one more JDK 25 round replaced one whose Gradle daemon was killed from
outside, 37 checks in, none failed); `checkUdpOff` and `checkUdpJni` 17/17 on both. libclaj still builds for
the four targets with zig 0.17.1, 4,424-4,736 B each.

### Measured (VM `bx_br5g57tj`)

`large`, 8 vCPU, kernel 6.8.0-117, OpenJDK 25.0.5.1; relay on CPUs 0-1, bench on 2-7, v7's `measure.sh` (loads A,
500 rooms × 8 clients, 256 B, 20,000 echoes/s over TCP, and C, the same with UDP clients). Base: the v7 relay jar
(`f40048c`) with its bench; final: the server jar of `39cf2a4` with `--stream` and its bench (the two benches
differ only in how they start the server). The later commits don't touch the relay's loop. 3 reps, the order
alternated within each. Per offered echo; every run passed its step with 100% delivery.

| Load | Build | System calls | Instructions | Relay CPU (mean) | p99 | Steal |
|---|---|---|---|---|---|---|
| A | base | 4.10-4.21 | 60.6-64.7k | 26.2-31.3 (29.3) µs | 1.17-2.85 ms | 0.2-0.7% |
| A | final | 4.11-4.13 | 60.8-61.6k | 25.6-27.9 (26.5) µs | 0.98-2.27 ms | 0.1-0.3% |
| C | base | 2.01-2.14 | 36.3-36.9k | 18.1-20.6 (19.1) µs | 0.37-0.60 ms | 0.1-0.5% |
| C | final | 2.02-2.16 | 36.4-37.0k | 18.2-23.9 (20.2) µs | 0.37-2.08 ms | 0.0-2.4% |

No difference beyond the spread. The final run at 23.9 µs and 2.08 ms at C is the one at 2.4% steal; without it,
final is 18.2-18.6 µs. The split by kind is the same (A: writev 2.13-2.14, readv 0.94-1.07, read 0.74-0.81,
epoll_wait 0.17-0.22 per echo for final; base within the same ranges).

**2.4's load A** (the same load, 2.x protocol, no stream relay), upstream's server jar (`435117c`, the same server
code as `f40048c`) against the final one, 3 + 4 + 6 alternated reps (`series.txt`, `series24.txt`, `series24b.txt`):

| Build | Passing runs | System calls | Instructions | Relay CPU | p99 |
|---|---|---|---|---|---|
| upstream | 11 of 13 | 6.88-7.56 | 185-236k | 76.2-83.8 µs | 0.94-9.86 ms |
| final | 12 of 13 | 6.45-7.56 | 172-235k | 76.3-86.7 µs | 0.97-43.5 ms |

The first series put final's p99 above upstream's in 2 of 3 pairs (9.3-41.5 against 3.4-9.9 ms), so two more
series ran: a variant of the final jar without the manifest's `Add-Opens` and `Enable-Native-Access` (which change
how `NioUtils` reaches the selector: reflection instead of `Unsafe`) had the same tail (2.4-27.9 ms), and with GC logs
the tails follow steal on both jars (final 43.5 ms at 2.0% steal, upstream 66.6 ms and a failed step at 1.6%), with
the same GC (12 pauses, 8 young, none full, longest 32-73 ms on both). The quiet pairs are equal (1.14/1.17,
0.94/0.97 ms). A fourth rep of `series24` failed its step on all three jars (upstream at 174 ms and 0.5% steal, the others at
4.5-4.9% steal), as did upstream's last run of `series24b`: they are left out of the ranges. Upstream's p99 tail
at this load is thus long too; the reps can't rank the two jars, and nothing in the 2.x path changed beyond a
volatile read per posted task.

**2.4 smoke** (`smoke.txt`, `claj-bench compare`, upstream then final, 100 rooms × 4 clients, 256 B): the ramp
(5k ×1.5 to 120k echoes/s), a ramp with half the clients over UDP, and `dual-thread=false`: both jars sustain the
same last step in each (75.9k, 113.9k and 113.9k relayed/s) and fail the same next one. Their logs hold only one
kind of error, the existing "Failed to remove connection … The room has been closed" when the bench closes
everything at once: 6, 57 and 59 for upstream, 37, 71 and 61 for final, varying with timing.

**Memory** (`<raw data>/v8/probe/`, live heap from `GC.class_histogram`, 2,000 of each, JDK 25):
an idle pending connection 1,063 → 1,435 B (+372 B: the `ClajConnection`, its `Ratekeeper` and Arc's `Connection`
fields), an idle host 2,391 → 3,547 B (+1,156 B: `ClajRoom`'s own state, its `NetworkSpeed` and `Ratekeeper`, the
`types` entry and the AFK timer). The budget's reservations are unchanged in kind (see above), so a budget still
admits about as many rooms as before.

**After the v8 review's fixes** (VM `bx_kq9xumh9`, `large`, kernel 6.8.0-117, OpenJDK 25.0.5.1, deleted after; the
same `measure.sh`): the main loop's structure changed (the `poll()` turn), so load A ran again, the server jar of
`151e79f` (base) against the fixes' (fix), both with `--stream` and the same bench, in two alternated pairs
(`<raw data>/v8/fixes/vm/results/`). Every run delivered 100%.

| Build | Steal | System calls | Instructions | Relay CPU | p99 |
|---|---|---|---|---|---|
| base | 0.1-0.2% (3 runs) | 4.34-4.55 | 63.4-65.1k | 17.7-23.3 µs | 0.45-1.87 ms |
| fix | 0.1% (2 runs) | 4.50-4.52 | 64.9-65.1k | 19.3-19.4 µs | 0.52 ms |
| base | 6.0% | 3.95 | 67.2k | 29.9 µs | 110 ms, step failed |
| fix | 2.3-3.9% | 4.22-4.23 | 67.6-68.5k | 26.3-26.7 µs | 13.4-24.3 ms |

No difference beyond the spread: at low steal the fix sits inside base's range, with the same instructions and
system calls. With the 2.x side idle, few tasks reach the main thread in this load, so the new branch should
rarely run (not counted). The 2.x protocol's
load A on both jars (no stream relay): base 69.3-72.9 µs, 7.17-7.25 calls, 205-229k instructions, p99 0.82-1.78 ms;
fix 65.1-70.9 µs, 7.23-7.25 calls, 222-242k instructions, p99 0.98-1.14 ms; 2 runs each at 0.1-0.6% steal, all
passing.

### Lines

`git diff --shortstat 435117c`, per part:

| Part | v7 (`f40048c`) | v8 |
|---|---|---|
| the relay's main sources | `stream-relay/src` 2,957 (12 files) + `claj-native` 249 (4 files) | `server/.../stream` 3,152 (11 files) + `server/native` 102 (3 files) |
| `server`'s existing classes | 0 | +337 −68 (10 files) |
| the checks and their helpers | 2,137 | 2,766 (4 files) |
| `bench` | +403 −50 | +409 −50 |
| `build.gradle`, `settings.gradle` | +116 −1 | +83 |
| `stream-notes` | 1,818 | 2,022 |
| total | +7,680 −51 (33 files) | +8,871 −118 (41 files) |

At `151e79f`, before the v8 review's fixes: 3,095 + 102, +189 −37 (9 files), 2,376 (3 files), 1,908, and
+8,162 −87 (39 files).

## Shared port with 2.4.x (assessment, not built)

Both protocols are server-first, which makes it possible on the wire. A shared acceptor would send Arc's
`RegisterTCP` (`00 06 FE 04 <i32 id>`) to everyone. A 2.4.x client goes on as today (UDP `RegisterUDP`,
then CLaJ packets). A stream client would answer with a new framework message (`FE <new id> …`) over TCP,
and the server would continue with `HELLO`. Datagrams are distinguishable too: Arc's start with `FE 03`
(6 bytes), `HELLO_UDP` with `03` (9 bytes). Costs and problems:

- one more round trip for stream clients, unless the token rides in `RegisterTCP`'s `i32`. 32 random bits
  are enough with the same-IP rule and a 5 s single-use window, but weaker than 64.
- the acceptor must hand legacy connections to Arc's `Server`, which can't adopt an accepted socket. The
  alternatives are reimplementing Arc's registration or proxying over loopback, which costs a copy, a socket per
  legacy connection and the client address (2.4.x per-address limits and bans would see 127.0.0.1).
- a TCP-only legacy client sends nothing after `RegisterTCP`, so the classifier needs a bounded undecided state.

Recommendation: keep a separate port until 2.4.x is retired.

## io_uring: assessed and dropped

Built on `feat/stream-io-uring` (`fdd0544`, `3068f20`, `623eeff`, review fixes `e4471a1`, merged in `11c7e38`) as a
system call batcher under the selector: the TCP sends and receives of each step of a loop turn in one
`io_uring_enter`, opt-in, about 940 lines (300 of them C). Removed on the maintainer's call; the last commit with
it is `11c7e38`, and its full write-up is `stream-notes/io-uring.md` there.

- **Measured** (VM `bx_2xsseacu`, 3 interleaved pairs against `01cb4e1`): system calls per echo fell at every load,
  A 4.39-4.50 → 3.22-3.34, B 6.54-6.65 → 5.43-6.00, C 2.22-2.42 → 1.74-2.12. CPU per echo moved only at twice A's
  rate (40,000 echoes/s), 17.7 → 13.8 µs on average, p99 1.23-1.58 → 0.48-0.60 ms. At A, B and C a batch held
  1.2-1.6 operations and the CPU stayed within noise: one io_uring operation costs about the system call it
  replaces, and the time is in the kernel's TCP stack (69% of the profile under the socket writes at A, round 4).
- **Why dropped**:
  - the security record and the default blocks: Docker ≥ 25 ([moby#46762](https://github.com/moby/moby/pull/46762)),
    containerd ≥ 2.0 ([containerd#9320](https://github.com/containerd/containerd/pull/9320)) and Podman/CRI-O
    refuse it in their default seccomp profiles; RHEL 9.3+ ships `kernel.io_uring_disabled = 2`
    ([9.3 release notes](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html-single/9.3_release_notes/9.3_release_notes));
    Google disabled it on ChromeOS and its production servers after its kCTF results
    ([June 2023](https://security.googleblog.com/2023/06/learnings-from-kctf-vrps-42-linux.html));
  - the review found 3 major bugs in the batcher (published SQEs left after a failed enter sent a byte to a reused
    descriptor; the sweep before the batched reads timed out a live host; a turn error stopped the loop), all
    fixed, a sign of what a second I/O path costs to keep right;
  - the hot spot is the kernel TCP stack, not the system call boundary (2.8% of the profile at A).
- **What went with it**: `uring.c`, `Uring`, `UringBatch`, the `ioUring` setting, the `-Puring` check option,
  the `idle-ready-host` check (it tested io_uring's read order), and the io_uring hooks in `ByteRing`, `StreamRoom`,
  `StreamConnection`, `Datagrams`, `BatchedDatagrams` and `StreamRelay`. `error-isolation-tcp-client` stays: it
  checks the channel path too.

## What the stream relay still lacks from 2.4.x

Done in v8 through the server's classes: rate limits, blacklists, `ClajEvents` and plugins, the console, the config
file, AFK closure, statistics in `status`. Still missing, each needing a wire addition: text and popup messages to
a host (`say`, `alert`), the server-closing warning before the close (the close itself is `ROOM_CLOSED serverClosed`),
state requests (`requestState`), large room lists (one message, truncated); and on the peer side a
`ProxyClient`/`VirtualConnection` adapter in `api` and the mod's provider.

## Open questions for the maintainer

1. **Token**: 64 random bits, single use, plus the same-IP rule for UDP. Enough, or bind more (e.g. the TCP port)?
2. **Stage 2 over UDP, the forged-source risk** (implemented on your direction, risk annotated, not solved; see
   protocol.md "Stage 2 over UDP"). A forged datagram from a bound address acts for that connection, a join
   included, and a 3-byte request (a minimal `ROOM_LIST_REQUEST`) can make the relay send up to 4,096 B to the forged address. Accepts stay on TCP,
   the UDP room list holds one datagram. When the risk stops being acceptable: echo the token in every UDP request?
3. **Transition rule**: pipelined bytes after a join are refused and closed. Alternative: treat them as stream bytes
   when the join is accepted, and as garbage otherwise.
4. **Who accepts a join**: the server alone (password, type, room limit), like 2.4.x. Should the host approve?
5. **EOF is `FIN`, not `CLOSE`**: settled by `idleTimeout`: a client that stopped sending is closed after the
   window with reason `timeout`, unless the host closes it first. Still open: an EOF or reset behind uncredited
   payload is only seen at the next grant, as the relay doesn't read a client without credit (TCP keepalive drops
   a vanished one).
6. **Broadcast `DATA`**: to acknowledged streams, within the smallest credit. Keep it, or let hosts send N copies?
7. **NAT rebinding**: datagrams from a new port are dropped. Add an authenticated re-association?
8. **`OPEN` carries the 2.4.x address hash**: fine for bans, or send nothing?
9. **Versions**: `HELLO` has the wire version (1); `SERVER_INFO` and `ROOM_CREATE` use the CLaJ major version
   (4), which must match exactly.
10. **Defaults**: timeouts, caps, budget, 64 KiB window, 4 KiB slice and datagram limit, 16 KiB host queue,
    loopback default bind in `Main`. `sliceSize=16384` measured 1.5× faster for bulk on loopback, at the latency
    cost above; WAN wasn't measured. `maxPendingPerAddress=8` can make users behind one NAT refuse each other's
    simultaneous handshakes.
11. **Kernel buffers**: a slow client can hold several MiB in the relay's socket send buffer (Linux autotuning
    up to `tcp_wmem` max), outside the budget. Cap with `SO_SNDBUF` (measured harmful in the stream-nio lane)
    or leave it to the operator's sysctl?
12. **Room list**: one message, at most 64 KiB over TCP and 4 KiB over UDP. Page it?
13. **UDP after the hello**: IP+port is the only authentication of later datagrams. A forged host datagram with
    conId 0 fans out to every associated client (up to 64×); a forged client datagram reaches its host. Sign the
    host datagram envelope, or limit UDP broadcast and rates? (Not exploited: no raw sockets here.)
14. **Idle stage 3 and capacity**: one address can create rooms up to the budget and `maxRooms`; after admission
    there is the 12 s `idleTimeout`, but no partial-frame deadline. Per-address room limits and an AFK policy are part of the deferred port.
15. **Credit return rule**: tried in round 3, returning credit only by 32 KiB while the host had over 32 KiB left,
    saved about 0.7-1 call per echo (row 4 above) but withheld up to 32,767 B from a quiet stream and could hold a
    host's next whole frame indefinitely. Reverted to "half a window, or whenever the client's queue empties" on
    the review's advice. A timed flush of small returns would get both, at the cost of a timer and state: wanted?
16. **State-changing requests on TCP only?** Requests over UDP need the client rules of protocol.md ("Stage 2 over
    UDP") because TCP and UDP share no order: one state-changing request at a time and alone, accepts final,
    `ROOM_CONFIG` over TCP. The alternative is to keep `JOIN_REQUEST`, `ROOM_CREATE` and `ROOM_CONFIG` on TCP and
    allow only queries (version, room info, room list) over UDP: that removes this class of ordering problem by
    construction, at the cost of narrowing "things can be sent via TCP or UDP" at stage 2. Your call.
17. **Not tried in round 3** (4 of 6 experiments used, the hard stop came first for the rest):
    - the selector: tried in round 4, see Benchmarks. `select(Consumer)` kept on Java 11+, `NioUtils` rejected.
    - the UDP path: `DatagramChannel.receive` returns an address per datagram, and the relay looks the peer up by
      it; no Java 8 API avoids it short of a connected channel per peer, which costs a descriptor and a key each.
      Batching with `recvmmsg`/`sendmmsg` is the UDP lane's (`feat/stream-udp-mmsg`).
    - socket options (`SO_SNDBUF`, `SO_RCVBUF`, `TCP_NODELAY` is already on): no system call to save, only latency or memory.
    - a write per hop is close to the floor now; `readv` with one segment could be `read`, same count.
    - Earlier ideas still open: an adaptive slice beyond the lone-stream rule, a larger shared read buffer,
      capping `SO_SNDBUF`, several relay processes, WAN latency and loss runs, a Mindustry host and client adapter.
18. **Round 4 left open.**
    - Experiment 1's CPU: at A, 9% more CPU per echo with 11% fewer instructions and 10% fewer calls, in 3 of 3
      reps at under 0.3% steal, on a VM where A kept the relay 88% busy. Whether it is kernel time, a saturation
      effect or the VM wasn't settled: the follow-up runs met 6-21% steal. A quiet machine at a lower rate
      (`perf stat -e cycles:u,cycles:k`) would tell.
    - The "buffer pointer" is on hold: copies are 0.5-4% of the profile, so it can't save more than that.
    - io_uring: measured, then dropped, see "io_uring: assessed and dropped".
