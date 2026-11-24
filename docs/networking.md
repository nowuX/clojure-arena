# Networking

## HTTP ownership

`server.clj` starts http-kit with Ring file/content-type/HEAD middleware.
`make-handler` serves only the selected public directory, routes `GET /health`
to UTF-8 `ok`, and returns 404 for missing files and unsupported routes.
`parse-options` validates a nonblank host and a CLI port in 1–65535. The
programmatic `start!` accepts port 0 for isolated ephemeral tests; `stop!` waits
for shutdown so the same port can be rebound.

Use [Development](development.md#run-the-shared-arena) for build, server,
watcher and Tailscale commands. A healthy `/health` does not establish that
`/js/main.js` exists, that a browser started, or that gameplay is connected.
A same-host request does not prove remote access.

The JVM runner discovers `.clj`/`.cljc` test namespaces, uses bounded HTTP waits
and isolated temporary public roots, and shuts down agents on completion.
Node client tests remain `just test`. The WebSocket contract below carries diagnostics and authoritative gameplay.

## EDN WebSocket boundary

`GET /ws` upgrades a WebSocket or returns 426. The server sends `{:type :ready}`;
the client sends exact `{:type :ping}` and receives `{:type :pong}`. Decode
exactly one EDN value using the EDN reader, with no evaluation. Errors are exact
`{:type :error :code ...}` maps: `:invalid-edn` for decoding/multiple values,
`:invalid-message` for malformed shapes or binary frames, `:unsupported-type`
for unimplemented keyword message types. Internal join/tick/leave are not wire
commands. A rejected message does not poison the next valid frame.

`transport/websocket-url` derives `/ws` from the page origin, changing HTTP to
WS and HTTPS to WSS. An HTTP(S) `server` query parameter selects another origin.
The [watched-development instructions](development.md#watch-while-using-the-game-server)
select the gameplay server while the watcher serves assets separately. The
watcher has no gameplay WebSocket; without the server query, it falls back locally.

`init` starts transport; `stop!` detaches callbacks and closes its owned socket.
Callbacks check their socket identity. Failure keeps the local fighting sandbox
playable. There is no automatic reconnect. An admitted OPEN connection now uses the server clock; snapshots provide shared rendering.

## Serialized admission

`room/initial-room` contains private `:members` and `:next-player-number`.
`apply-room-message` accepts trusted exact join/input/tick/leave maps.
A join is `{:type :join :connection-token ...}`. Its pure result is an envelope `{:room next-room :effects [...]}`.
A free palette keyword is allocated with monotonic `:player-N` identity; the
fifth connection receives exact `{:type :room-full}` without changing the room.
A duplicate token keeps its original identity and emits that captured reply.

`make-room` owns one Clojure agent. `enqueue-room-message!` serializes actions
with `send`; its watch delivers effects after the new envelope is committed.
The server maps fresh private Object tokens to channels; neither token nor
client claims determine a wire-selected ID. Each server creates its own room.

Wire identity is exactly `{:type :identity :player-id :player-N :color :cyan}`
with one of `:cyan/:crimson/:amber/:green`. The browser shows ID/color. Socket closure or heartbeat expiry queues atomic leave cleanup. Accepted identities own waiting player records as described below.

## Admission and world data

The room begins with the shared platforms and an empty `:players` map, not the
four offline fixtures. `spawn-config` maps palette keywords to body fill and
spawns `(340,160)/(380,160)/(420,160)/(460,160)`. `make-spawn-player` delegates
to the shared factory, including spawn lock, facing, jump/drop, percentage,
melee sequences/cooldown, retained motion, stocks/lifecycle and stored spawn.

The same admission transition inserts the member, body under that ID, and
`protocol/neutral-input` in `:inputs-by-player`. Duplicate admission preserves
all existing body/input state. Full-room rejection inserts nothing. The world
is serializable data; private channel tokens belong only to membership and
captured delivery recipients. `match/add-player` wraps the factory record as
waiting with zero stocks/timers. The second admission starts a countdown;
late joins remain observers outside the frozen contender roster. Match phase
transitions and running gameplay share the same 40 Hz server scheduler.

## Connection-owned input

The browser sends exactly `{:type :input :horizontal -1|0|1 :jump? boolean
:drop? boolean :attack? boolean :dash? boolean :antiknockback? boolean
:recover? boolean :grenade? boolean :nova? boolean}` on fresh control events,
without waiting for RAF. The five ability flags are intentions from Shift
(either key), Q, E, R and F; they carry no effect values, and the simulation
does not act on them yet. It never sends an identity or connection token. Before admission, the
transport coalesces the latest direction and ORs pending one-shot actions.
Closing or replacing the socket clears this buffer.

The server attaches its private connection token. `apply-room-message` resolves
that member and merges only its input; it does not move the world. A neutral
release preserves an already captured jump, drop, attack or ability intention
until simulation consumes it for an active running contender. Waiting, observer, respawning,
eliminated and finished records discard new one-shot actions immediately. Keyboard repeat sends no extra action; both Shift keys share one press, and
Ctrl/Meta/Alt combinations stay browser shortcuts. Blur sends neutral movement
immediately. Only disconnected fallback runs local physics.

## Authoritative clock

`room/tick-seconds` is 0.025. Trusted exact `{:type :tick}` actions call
`match/step` once with `:inputs-by-player`, increment `:tick`, consume one-shot
flags and retain direction. Input and join actions share the same agent queue.
`ticker/make-driver` admits at most one pending tick; overloaded pulses are
dropped without catch-up, so overload slows simulation time. Its owned daemon
scheduler stops before the HTTP handle is released.

`transport/connected?` requires OPEN and accepted admission. The client keeps
RAF/drawing but discards connected elapsed time and local action copies. A
transition between local and authoritative ownership resets fractional time.
The admitted display renders committed snapshots.

## Committed snapshots

Every tick with members captures one effect with `:connection-tokens` and an
exact `{:type :snapshot :tick n :world ...}` message from the same next room.
Only world data crosses the wire: exactly `:blast-bounds`, `:platforms`, `:players`
and `:match`, validated
with exact record keys, finite numeric fields, unique platform IDs, and valid
player/drop/target identities. Private tokens stay in delivery metadata.

The post-commit watch submits snapshots to `publication/publish!`. Its bounded
one-item queue replaces an older pending snapshot; the publication worker
encodes once and broadcasts outside the room queue. A slow send cannot hold
physics. The ticker and publisher are stopped with their own server handle.

Transport accepts snapshots only for its current OPEN, accepted socket, with
the assigned player present and a tick greater than the previous accepted tick.
`client` installs the value immediately. Each RAF passes one captured world to
both Canvas and panel. No interpolation or local prediction is added. On close,
the separate saved offline world is restored; it never simulates a network
world or replays the connected interval.

## Full-room rejection

A fifth join changes no accepted player or allocation counter. The browser
shows «La sala está llena; el juego continúa en modo local.», clears identity,
snapshot and buffered input, and closes the unaccepted socket. Late callbacks
cannot replace this terminal notice. Only an explicit new `start!`/page reload
starts a fresh attempt; there is no retry loop.

## Leave and liveness

Each socket owns an idempotent retirement gate. Close and expiry remove its
channel mapping, unregister heartbeat and enqueue trusted `:leave` once.
`apply-room-message` removes the member, body and pending input atomically;
unknown/retired tokens change nothing. A freed color is reusable, but the next
player ID is always larger. Input arriving after retirement is ignored.

Heartbeat uses WebSocket control Ping/Pong, independent of EDN diagnostics and
25 ms simulation time. A daemon pulses every 5 seconds. A monotonic 15-second
deadline accepts only the current matching challenge before expiry; input and
EDN `:ping` cannot renew it. Expiry removes gameplay ownership even if delivery
of `{:type :error :code :connection-timeout}` fails. A responsive browser
preserves the Spanish expiry notice, clears session data, requests closure and
restores local fallback. Browser control Pong replies are automatic.

Server shutdown stops ticker, heartbeat and publication, closes its HTTP
channels, then waits for queued room cleanup. Tests use owned ephemeral servers;
unplugging a physical device remains a separate manual check.

## Fresh connection sessions

Every `start!` discards the previous socket, callbacks, identity, pending input
and snapshot tick. Callbacks also check their captured socket against the
current one, so even a retained late callback cannot alter its replacement.
`stop!` does not reconnect automatically.

Cookies and tabs are not identity credentials. A reload is an ordinary join:
it can be rejected while old cleanup is pending, and it receives a larger ID
and fresh factory state after a slot is released. Color reuse does not restore
old percentage, motion, strike, input or snapshot state. The room tick continues
across joins/leaves rather than restarting for an individual session.


## Lifecycle snapshots

Player schema includes `:stocks` (integer 0–3), `:lifecycle`
(waiting/active/respawning/eliminated), finite `:spawn-x/:spawn-y`, and finite timers
`:respawn-seconds-left` (0–3) / `:protection-seconds-left` (0–2). Active players
have stocks and no respawn countdown. Respawning records have stocks and no
protection; eliminated records have zero stocks and zero timers. Dash state is
`:dash-ticks-left` (integer 0–8, nonzero only for active bodies),
`:dash-cooldown-left` (finite 0–2.5) and `:dash-stopped?` (true only during an
active dash). Q state is `:antiknockback-seconds-left` (finite 0–5, nonzero only
for active bodies), `:antiknockback-cooldown-left` (finite 0–10) and
`:antiknockback-buffered?` (true only during an active dash). Neither
stock loss nor elimination removes membership or snapshot delivery. Only
socket retirement removes the body record and frees its color.

## Public match snapshots

The exact world fields are `:blast-bounds`, `:platforms`, `:players` and `:match`.
See [Matches](matches.md) for the exact phase/roster/result contract. Waiting
records retain ownership in snapshots with zero stocks, whereas eliminated
contenders remain in the fixed roster. A forfeited contender may outlive its
removed player record; a committed winner may therefore be disconnected.
Reset preserves current IDs/colors and monotonically increasing room ticks,
clears the old roster/result and admits current observers into the next start.
No client message can select phase, winners, clocks or reset a match.

## Browser diagnostics

The connection status names local/shared mode and the attempted gameplay
WebSocket URL. Shared mode requires an OPEN socket and accepted admission;
an error label never transfers clock ownership. Address parsing, WebSocket
construction, network events, send failures, unreadable EDN, invalid server
shapes and typed server errors have distinct Spanish messages. Browser network
errors do not identify a specific failure cause.

Only accepted replies and newer applicable snapshots clear transient errors.
Ignored snapshots do not hide them. Closing retains the endpoint, last error
and available close code/reason, while removing ordinary session ownership.
Full-room rejection and expiry remain terminal until an explicit new session.
Replacing a socket discards its diagnostics and ignores its late callbacks.
