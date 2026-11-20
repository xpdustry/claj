# CLaJ stream protocol, version 1

The wire protocol of the server's stream relay. Constants are in
[`StreamProtocol.java`](../server/src/com/xpdustry/claj/server/stream/StreamProtocol.java).
All integers are big-endian. TCP and UDP share one port, separate from 2.4.x (`stream-port`, off by default).

Every connection goes through three stages:

1. **Hello**: the server speaks first and gives a token. The peer confirms it over TCP (TCP-only) or
   from a UDP socket (TCP+UDP), like Arc's `RegisterTCP`/`RegisterUDP`.
2. **Control**: framed requests and replies over TCP. A denied request keeps the connection here,
   so the peer can retry or ask something else.
3. **Layer 4**, after the join or the room creation is accepted:
   - a **client**'s TCP is a raw byte stream to the host until it closes, its UDP datagrams go to the host as they are.
     A standard client gets its `java.net.Socket` back and uses it like any other one;
   - a **host**'s TCP carries the mux of its room.

The relay is game-agnostic: payloads are opaque, nothing is Mindustry-specific.

## Stages 1 and 2: messages

A message on TCP is `u16 length` then `length` bytes: `u8 type` and its body (`length` ≥ 1).
Over UDP, a message is one datagram, without length: `u8 type` and its body (see "Stage 2 over UDP").
Bodies reuse the packets of `common` (`Packet.read/write`); their layouts are spelled out below.
`type` is `u8 length` + 1..16 ASCII bytes (`ClajType`).

| Type | Name | Direction | Body |
|---|---|---|---|
| 1 | `HELLO` | S→P, at accept | `u8 version=1, i64 token` |
| 2 | `HELLO_TCP` | P→S | `i64 token`: TCP-only |
| 3 | `HELLO_UDP` | P→S, UDP datagram | `i64 token`: associates the datagram's source address, resent every 100 ms |
| 4 | `WELCOME` | S→P | `u8 flags` (bit 0 `FLAG_UDP`): end of stage 1 |
| 10 | `VERSION_REQUEST` | P→S | empty |
| 11 | `SERVER_INFO` | S→P | `i32 version`, the CLaJ major version (4 for 2.4.5) |
| 12 | `ROOM_INFO_REQUEST` | P→S | `i64 roomId` |
| 13 | `ROOM_INFO` | S→P | `i64 roomId, bool protected, type, u16 clients, u16 maxClients, u16 n, state[n]`; `maxClients` is the room's own limit, 0 for the server's, as 2.x's `RoomInfoPacket` |
| 14 | `ROOM_INFO_DENIED` | S→P | empty: no such room. Counts as denied |
| 15 | `ROOM_LIST_REQUEST` | P→S | `type` |
| 16 | `ROOM_LIST` | S→P | `i32 count`, then per public room of that type: `i64 roomId, bool protected, u16 n, state[n]`. Truncated to one message |
| 17 | `JOIN_REQUEST` | P→S | `i64 roomId, bool withPassword, i16 password, type` |
| 18 | `JOIN_ACCEPTED` | S→client | `i64 roomId`. **Last framed message**: the next byte each way is stream data |
| 19 | `JOIN_DENIED` | S→P | `i64 roomId, u8 RejectReason`. Counts as denied |
| 20 | `ROOM_CONFIG` | P→S | `u8 flags` (bit 2 public, bit 1 protected, bit 0 requestState, ignored), `i16 password, u16 maxClients` (0: server limit). No reply, applies to the next `ROOM_CREATE`. Also valid in a host `CONTROL` frame |
| 21 | `ROOM_CREATE` | P→S | `u16 0, i32 version, type` (the 2.4.x layout) |
| 22 | `ROOM_CREATED` | S→host | `i64 roomId`. **Last framed message**: the next byte each way is a mux frame |
| 23 | `ROOM_CLOSED` | S→P | `u8 CloseReason`: refused creation (stays in stage 2), or room closed by the server (in `CONTROL`) |
| 24 | `ROOM_STATE` | host→S, in `CONTROL` | `u16 n, state[n]`, n ≤ 1024 (more is a violation, where 2.x truncates), shown in info and list |
| 25 | `ROOM_CLOSE` | host→S, in `CONTROL` | empty: closes the room |

`RejectReason`: 0 error, 1 serverFull, 2 serverClosing, 3 roomNotFound, 4 roomFull, 5 passwordRequired,
6 invalidPassword, 7 incompatible. `CloseReason` (`common`'s enum, in its order), with where the relay sends each:

| Ordinal | Name | Stage 2 `ROOM_CLOSED` (creation refused) | `CONTROL` `ROOM_CLOSED` (room closed) |
|---|---|---|---|
| 0 | `error` | pipelined `ROOM_CREATE`, already in a room, creation failed; `ROOM_CONFIG` over UDP | |
| 1 | `closed` | | the host's `ROOM_CLOSE` |
| 2 | `obsoleteClient` | no type, or a negative version | |
| 3 | `outdatedClient` | version below the server's | |
| 4 | `outdatedServer` | version above the server's | |
| 5 | `serverClosed` | server closed, or creations blocked | server shutting down or stopped |
| 6 | `serverFull` | `max-rooms`, the memory budget, or the address's `room-limit` | |
| 7 | `blacklisted` | type in `type-blacklist` | |
| 8 | `afk` | | no client joined for `afk-time` |
| 9 | `spam` | | only from the console: a stage 3 host past `host-rate` is slowed down, not closed |

The console's `close room <id> [reason]` closes a room with any of them. A peer must accept unknown ordinals too.

Rules:

- **Token.** 64 random bits (`SecureRandom`), never 0, single use, valid during stage 1 only. A `HELLO_UDP`
  counts only if its source IP is the TCP peer's IP. Wrong or foreign token over TCP closes the connection;
  bad datagrams are dropped without answer.
- **Lockstep.** The server handles one request at a time and reads the next one only once the reply is written.
  A peer that pipelines requests is served in order; one that never reads holds at most one reply.
- **Transition.** Nothing may follow `JOIN_REQUEST` or `ROOM_CREATE` before the reply is read: the peer can't
  know if it will be accepted, and a pipelined byte would be read in the wrong stage. If bytes already follow
  the request when the server handles it, it answers `JOIN_DENIED error` / `ROOM_CLOSED error` and closes.
  Bytes sent after a denial are parsed as stage 2 messages (usually a violation, so a close).
- **Violations close the connection**: unknown type, bad length (0 or > 128 for requests), trailing or missing body bytes.

### Stage 2 over UDP

After the `WELCOME`, a datagram from the bound address is a stage 2 request of that connection: `u8 type + body`,
the same types and bodies as over TCP, with the same request and denial counts, and violations close the
connection. The reply goes back over UDP to the bound address, as one datagram. TCP and UDP share no order: the
relay handles datagrams as they come. Exceptions and differences:

- **`JOIN_ACCEPTED` and `ROOM_CREATED` always arrive over TCP.** They are the last framed message before TCP turns
  raw, so they belong in the TCP stream, right where the switch happens: the peer reads TCP messages up to the
  accept, and everything after it is stage 3. Over UDP, a lost or late accept would leave the peer unable to tell
  where its TCP stream starts. One rule, no extra state. Denials (`JOIN_DENIED`, `ROOM_CLOSED`) come over UDP.
- **The room list over UDP holds what fits one 4,096 B datagram** (`MAX_DATAGRAM_SIZE`), with the same truncation
  as the 65,535 B TCP message. No paging: a peer that wants the whole list asks over TCP. Every other reply is
  under 1.1 KB (a room state is at most 1,024 B), so it always fits.
- **Transitions read TCP first.** Before acting on a UDP `JOIN_REQUEST` or `ROOM_CREATE`, the relay reads and
  handles the TCP requests its socket already holds, so their replies come before the accept. If TCP bytes are
  still unhandled after that (a partial request, or one waiting for an earlier reply to be written), the request
  is refused with `error` and the connection closes, as a pipelined TCP transition. Bytes still on the network
  can't be seen: that part is the client's rule below.
- **`ROOM_CONFIG` only over TCP.** It has no reply, so over UDP nothing would order it before a `ROOM_CREATE`, and
  a password could silently be missing. Over UDP it is refused with `ROOM_CLOSED error` (a datagram, counted as
  denied) and nothing is applied. Sent over TCP before a UDP `ROOM_CREATE`, it applies, if it reached the relay
  first.
- **No lockstep over UDP**: each datagram is answered at once, never queued. Empty datagrams and a `HELLO_UDP`
  repeated in stage 2 are ignored (the peer resends it until the `WELCOME` arrives).
- **After a transition, a datagram is stage 3**: a request resent or delayed past the accept goes to the host as a
  payload (a client), or is read as `i32 conId + payload` and usually dropped (a host).

**Client rules** over UDP:

1. **One state-changing request at a time, alone.** Send a `JOIN_REQUEST` or `ROOM_CREATE` only when no other
   request is outstanding, on either transport: every earlier request has its reply, and an earlier `ROOM_CONFIG`
   was followed by a request whose reply came back over TCP (e.g. `VERSION_REQUEST`), so it was handled.
2. **An accept is final.** Once `JOIN_ACCEPTED` or `ROOM_CREATED` arrives over TCP, the connection is in stage 3;
   any stage 2 datagram reply arriving later (a delayed denial or query reply) is to be ignored, never acted on.
3. **What is safe to resend** over UDP after a timeout: queries (`VERSION_REQUEST`, `ROOM_INFO_REQUEST`,
   `ROOM_LIST_REQUEST`), which change nothing; each resend counts toward `maxRequests`. A `JOIN_REQUEST` or
   `ROOM_CREATE` may be resent only while still in stage 2 and with no reply of any kind to it yet; a reply that
   arrives later for the first one is to be read by rule 2 (accept: final) or as a denial of that attempt, which
   leaves the connection in stage 2. If the first one was accepted, the resend reaches the host as a stage 3
   datagram: a host should expect it. `ROOM_CONFIG` is never sent over UDP.

`StreamClient` sends every stage 2 request over TCP, one at a time, each after the previous reply: it follows
these rules by construction.

#### Known risk: forged source addresses

Accepted for now, on the maintainer's direction ("first ignore it and just annotate it"), and annotated at the
code site (`StreamRelay.controlDatagram`):

- **The source address is the only proof of the sender, and it can be forged.** Whoever can send a datagram with a
  bound address as its source acts for that connection: queries, room creation, **a join**, which turns the
  victim's TCP into a stream to a room it didn't choose, or a malformed request, which closes it.
- **Amplification**: a `ROOM_LIST_REQUEST` of at least 3 B (type, type length, one type character) can make the
  relay send up to 4,096 B to the forged address, up to about 1,365× in payload (UDP and IP headers aside). In
  stage 2 it is bounded per connection by `maxRequests` (32) and the stage 2 deadline (30 s); stage 3 forwarding
  is a separate risk (stream-relay.md, open questions).
- Not done, on purpose: session secrets, request ids or replay protection, transitions confirmed over TCP.
  Each is a small wire change (e.g. the token echoed in every UDP request) if the risk stops being acceptable.

## Stage 3, client

Raw bytes both ways. TCP half-close works: `shutdownOutput()` on the client becomes `FIN` to the host,
the host's `FIN` becomes `shutdownOutput()` on the relay's side once its queued bytes are written.
A datagram from the client's associated address goes to the host as is; a datagram for the client
arrives from the relay's address, payload only. When the stream ends from the host's side (`CLOSE`, or the room
closing), the relay writes the queued bytes, shuts its output down (the client reads them, then EOF) and discards
the client's input until its EOF, within the linger timeout: no reset that could cut the last bytes.

Without upstream credit the relay reads one byte ahead and keeps it for the next turn, so a client EOF right
behind it becomes `FIN`, and a reset `CLOSE`, without waiting for a grant.

**Timeout.** A client from which nothing was received, TCP bytes or datagrams, for `idleTimeout` (12 s) is closed,
and its host gets `CLOSE` with reason `timeout`. So **a client must send something within the window**: a game
does with its own traffic, but a plain socket user that may stay silent needs application traffic or its own
keep-alive bytes (anything its host ignores), or datagrams. A client that only waits for the host's credit, queued
for its turn or holding its read-ahead byte, has sent and isn't timed out. A client that shut its output down
(`FIN`) can't send anymore, so it is closed by this timeout too, unless the host closes the stream first.
TCP keepalive is on for every socket, to notice a peer that vanished: see `keepAlive*` below.

## Stage 3, host mux

**Timeout.** A host is held to the same `idleTimeout` as its clients: anything it sends counts (frames, UDP
datagrams), and a host with nothing to send sends `PING`. Arc's 2.x peers send a keep-alive after 8 s without
writing and time out after 12 s without reading, so a host sending `PING` 8 s after its last frame keeps that
margin. A host timed out closes its room: every client is closed, like any host leaving.

Frame: `u8 type, i32 conId, u16 length, payload[length]` (7-byte header). conIds start at 1 in each room,
are given in join order and never reused; `conId 0` is the room (`CONTROL`) or "every stream" (`BROADCAST`).

| Type | Name | Direction | conId | Length | Meaning |
|---|---|---|---|---|---|
| 1 | `OPEN` | S→H | stream | 9 | a client joined: `u8 flags` (bit 0: client has UDP), `i64 addressHash` (FNV-1a of the IP, as 2.4.x) |
| 2 | `DATA` | both | stream, or 0 from H | 1..65535 | stream bytes, consume credit. The server sends one slice (4 KiB by default) per frame while other streams of the room are queued, more when the stream is alone (see below) |
| 3 | `CLOSE` | H→S: 0, S→H: 1 | stream | | abort both directions. Bytes already accepted by the receiver are still delivered, then the socket closes. No reply. From the server: `u8 reason`, the `arc.net.DcReason` ordinal of 2.4.x's `ConnectionClosedPacket`: 0 `timeout` (nothing received for `idleTimeout`), 1 `closed`, 2 `error` (reset, I/O error) |
| 4 | `WINDOW` | both | stream | 4 | `i32 credit` > 0: the receiver accepts that many more `DATA` bytes |
| 5 | `FIN` | both | stream | 0 | no more `DATA` this way, after the queued ones. The other direction goes on |
| 6 | `CONTROL` | both | 0 | 1..2048 | a stage 2 message (`u8 type` + body) for the room: `ROOM_CONFIG`, `ROOM_STATE`, `ROOM_CLOSE` from the host, `ROOM_CLOSED` from the server |
| 7 | `DGRAM` | both | stream, or 0 from H | 0..4096 | one datagram, uncredited |
| 8 | `PING` | H→S | 0 | 0 | keep-alive of an idle host, ignored otherwise |

**Credits** (64 KiB per stream per direction):

- Host→client: the server grants 65536 right after `OPEN`. It returns credit with `WINDOW` as bytes are written
  to the client's socket: once 32 KiB are written, or when its queue for that client is empty.
  The host must never have more than 65536 bytes unacknowledged on a stream. A `DATA` past the credit means
  that client can't keep up: the server kicks it (`CLOSE` with reason `error` to the host, the frame is dropped)
  and the room goes on.
- Client→host: starts at 0. The host grants with `WINDOW` when it can take bytes, e.g. 65536 after `OPEN`,
  then as it consumes them. A grant that makes the outstanding credit exceed 65536 is a violation.
- The server sends `DATA` from ready streams in turn, one slice each (4 KiB by default), so a flooding client
  never has more than one slice in front of another stream **already in the ready queue with it**. A stream
  alone in the ready queue gets up to the free host queue in one frame (at most 20 KiB with the defaults), even
  if another stream is readable but not queued yet: that one waits behind at most those 20 KiB.

**Slow clients are the host's call.** The server measures no latency and sends nothing for it: credit already
says it. The server returns all of a stream's remaining credit as soon as its queue for that client is empty, so
"all the credit I spent came back" means "the server has no unwritten `DATA` queued for that client", one
host↔server delay late. Bytes the server wrote can still sit in its kernel's TCP send buffer, unread by the
client: credit bounds the server's application queue, not delivery. Recommended host rule, per client: start a
clock when bytes wait for credit or credit is outstanding, reset it when both are back to zero, and above the
room's maximum latency drop what the game can spare, then send `CLOSE`. This is a backlog policy, and it also
counts the host's own backlog, which the server can't see; a game that needs a bound on what the client has
actually consumed needs its own acknowledgements. A host that doesn't apply it only hurts itself: the server
queues at most the credit for a stalled client, reserved at admission, plus what the kernel buffers. Like idle
detection in 2.4.x.

**Broadcast** `DATA` with conId 0 goes to every stream the host already sent a frame for (`WINDOW`, `DATA`,
`FIN`) and didn't end with `FIN`, so a client never gets bytes before the host knew of it. Its length must fit
the credit of each of them, and is debited from each: a stream it doesn't fit is kicked like above, the others
get the frame. `DGRAM` with conId 0 goes to those streams that have UDP.

**Datagrams.** A UDP host exchanges `i32 conId + payload` datagrams with the relay's port (conId 0: broadcast).
A TCP-only host gets `DGRAM` frames instead; the server drops one when the host's queue holds more than 16 KiB.
A datagram for a TCP-only client, an unknown stream or a full socket buffer is dropped. Nothing is ever
retransmitted, reordered or queued for datagrams. A datagram may arrive before the `OPEN` of its stream.

**Violations close the room**: unknown type, host `OPEN`, bad lengths, a conId never given, `DATA` after the
host's `FIN`, a bad grant (`DATA` over credit only kicks its client, see above). Frames for a stream that is already closed are ignored.

## State machines

```
Client                                   Host
connect ─► read HELLO                    same stage 1 and 2
 ├─ TCP-only: send HELLO_TCP(token)      [ROOM_CONFIG] ─► ROOM_CREATE ─► ROOM_CREATED: mux
 └─ UDP: send HELLO_UDP(token)/100 ms               └──► ROOM_CLOSED(reason): stage 2
read WELCOME(flags)                      mux, per stream:
stage 2: VERSION / ROOM_INFO / ROOM_LIST   OPEN ─► grant WINDOW ─► DATA both ways
  JOIN_REQUEST ─► JOIN_DENIED: retry       FIN from client: may still send, then FIN or CLOSE
            └──► JOIN_ACCEPTED: raw stream FIN from host: client may still send, until its FIN
raw stream until close                     CLOSE either way: the stream is over
                                         CONTROL ROOM_CLOSED(reason): the server closes the room
```

Server side, per stream: `open` → (`client FIN` and `host FIN` and queue flushed → released, no frame) or
(`CLOSE` from the host → flush, shut the output down, discard input until EOF, all within the linger timeout,
then close) or (client error → `CLOSE` to the host). Host gone → the same for each client: its queued bytes, then EOF.

## Timeouts and limits (defaults, `ClajConfig` keys of the server's `config.json`)

The relay's own settings, and the 2.x ones it shares (marked *shared*: they count both transports together).
Timeouts are in seconds, as the 2.x ones.

| Key | Default | Effect |
|---|---|---|
| `stream-port` | 0 (off) | the relay's port, TCP and UDP; `bind-address` (default: every interface) binds both servers |
| `register-timeout` (*shared*) | 5 s | stage 1 deadline from accept, as Arc's registration in 2.x; the token expires with it (0: `control-timeout` only) |
| `control-timeout` | 30 s | absolute deadline of stages 1+2, not reset by activity; it caps the stage 1 deadline too |
| `linger-timeout` | 5 s | time to flush queued bytes and wait for the peer's EOF after a close |
| `idle-timeout` | 12 s | stage 3: a client or host from which nothing was received for this long is closed (0: off), unless the relay is the one slowing it down. Arc's connection timeout in 2.x |
| `keep-alive` | 10 s | TCP keepalive of every socket (0: off): the idle time is drawn up to 50% above it per socket (at most 32,767 s), then 3 probes at half of it. Set where the runtime ships the options (JDK 11+, and Java 8 builds such as the tested 8u512); elsewhere only `SO_KEEPALIVE`, with the system's delays (2 h on Linux) |
| `max-requests` | 32 | stage 2 requests per connection, denied ones included |
| `client-rate` (*shared*) | 2 MB/s | what a client may send, with a burst of 3 s. Past it the relay reads the client slower (TCP pushes back) and drops its datagrams; a 2.x client is kicked. A stage 2 request costs at least 1 KB and kicks, as a 2.x packet |
| `host-rate` (*shared*) | 2 MB/s | what a host may send, for each client of its room and itself (2 clients: 6 MB/s). Past it, the same; a 2.x room is closed with `spam` |
| `max-connections` (*shared*) | 1,048,576 | connections, both transports |
| `connection-limit` (*shared*) | 64 | connections per IP, both transports, stages 1-2 included |
| `max-rooms` (*shared*) | 65,536 | rooms, both transports |
| `client-limit` (*shared*) | 128 | clients per room, also the default room limit |
| `room-limit`, `join-limit`, `info-limit`, `list-limit` (*shared*) | 16, 32, 32, 32 | per IP and per minute |
| `memory-budget` | ½ max heap | every room reserves 26.0 KiB, every stream 64 KiB + 42 B, every connection 2 KiB; refusals say `serverFull` |
| `slice-size` | 4 KiB | largest `DATA` the server sends in one turn of a stream; 16 KiB is faster for bulk transfers. A host queue holds 4 slices of `DATA`/`DGRAM` before streams wait for their turn |
| `udp-batching` | `auto` | `recvmmsg`/`sendmmsg` through libclaj: `off`, `auto`, or `jni` to warn when unavailable |

Fixed: `TCP_NOTSENT_LOWAT` of every socket is 16 KiB, with libclaj.

Each of the relay's keys checks its value (e.g. `keep-alive` 0-21,844, `slice-size` 1-65535): the console
refuses an invalid one and `config reload` keeps the current value, with an error.

The blacklists (`blacklist`, `type-blacklist`) and `afk-time` apply too: a blacklisted address is refused at accept,
a blacklisted type gets `ROOM_CLOSED blacklisted`, an AFK room gets `ROOM_CLOSED afk`.

Fixed by this version: the header layouts, type numbers, 64 KiB window, 4 KiB datagram limit. The server's slice size
is its own setting: hosts must accept any `DATA` length.
Open (see `stream-relay.md`): the UDP control risk (above), token size and same-IP binding, the transition rule's enforcement,
broadcast semantics, the defaults above, NAT rebinding, a shared port with 2.4.x.
