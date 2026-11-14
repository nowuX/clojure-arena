# Networking

## HTTP ownership

`server.clj` starts http-kit with Ring file/content-type/HEAD middleware.
`make-handler` serves only the selected public directory, routes `GET /health`
to UTF-8 `ok`, and returns 404 for missing files and unsupported routes.
`parse-options` validates a nonblank host and a CLI port in 1–65535. The
programmatic `start!` accepts port 0 for isolated ephemeral tests; `stop!` waits
for shutdown so the same port can be rebound.

```sh
just build
just server 127.0.0.1 8082
just test-server
```

Open http://127.0.0.1:8082/. The server serves files already on disk; it never
compiles ClojureScript. A healthy `/health` does not establish that `/js/main.js`
exists, that a browser started, or that gameplay is connected. For remote use,
obtain `tailscale ip -4`, bind `just server <TAILSCALE_IP> 8082`, and open
`http://<TAILSCALE_IP>:8082/`. A same-host request does not prove remote access.

The JVM runner discovers `.clj`/`.cljc` test namespaces, uses bounded HTTP waits
and isolated temporary public roots, and shuts down agents on completion.
Node client tests remain `just test`. The WebSocket diagnostic contract below does not yet synchronize gameplay.

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
For example, serve assets with `just dev <TAILSCALE_IP>` and open
`http://<TAILSCALE_IP>:8081/?server=http://<TAILSCALE_IP>:8082` while
`just server <TAILSCALE_IP> 8082` runs separately. Plain `:8081/` targets the
watcher, which has no gameplay WebSocket; use the query or the server's :8082 page.

`init` starts transport; `stop!` detaches callbacks and closes its owned socket.
Callbacks check their socket identity. Failure keeps the local fighting sandbox
playable. There is no automatic reconnect. An admitted OPEN connection now uses the server clock; shared rendering is the next checkpoint.

## Serialized admission

`room/initial-room` contains private `:members` and `:next-player-number`.
`apply-room-message` accepts only trusted exact `{:type :join :connection-token
...}` maps. Its pure result is an envelope `{:room next-room :effects [...]}`.
A free palette keyword is allocated with monotonic `:player-N` identity; the
fifth connection receives exact `{:type :room-full}` without changing the room.
A duplicate token keeps its original identity and emits that captured reply.

`make-room` owns one Clojure agent. `enqueue-room-message!` serializes actions
with `send`; its watch delivers effects after the new envelope is committed.
The server maps fresh private Object tokens to channels; neither token nor
client claims determine a wire-selected ID. Each server creates its own room.

Wire identity is exactly `{:type :identity :player-id :player-N :color :cyan}`
with one of `:cyan/:crimson/:amber/:green`. The browser shows ID/color. Membership remains reserved after socket closure until
explicit leave handling is introduced. Accepted identities now spawn bodies as described below.

## Admission and world data

The room begins with the shared platforms and an empty `:players` map, not the
four offline fixtures. `spawn-config` maps palette keywords to body fill and
spawns `(340,160)/(380,160)/(420,160)/(460,160)`. `make-spawn-player` delegates
to the shared factory, including spawn lock, facing, jump/drop, percentage,
melee sequences/cooldown, and retained motion.

The same admission transition inserts the member, body under that ID, and
`protocol/neutral-input` in `:inputs-by-player`. Duplicate admission preserves
all existing body/input state. Full-room rejection inserts nothing. The world
is serializable data; private channel tokens belong only to membership and
captured delivery recipients. Server bodies are active records immediately;
match phases have not been introduced. The server advances them at 40 Hz.

## Connection-owned input

The browser sends exactly `{:type :input :horizontal -1|0|1 :jump? boolean
:drop? boolean :attack? boolean}` on fresh control events, without waiting for
RAF. It never sends an identity or connection token. Before admission, the
transport coalesces the latest direction and ORs pending one-shot actions.
Closing or replacing the socket clears this buffer.

The server attaches its private connection token. `apply-room-message` resolves
that member and merges only its input; it does not move the world. A neutral
release preserves an already captured jump, drop or attack until simulation
consumes it. Keyboard repeat sends no extra action, and blur sends neutral
movement immediately. Only disconnected fallback runs local physics.

## Authoritative clock

`room/tick-seconds` is 0.025. Trusted exact `{:type :tick}` actions call
`game/step` once with `:inputs-by-player`, increment `:tick`, consume one-shot
flags and retain direction. Input and join actions share the same agent queue.
`ticker/make-driver` admits at most one pending tick; overloaded pulses are
dropped without catch-up, so overload slows simulation time. Its owned daemon
scheduler stops before the HTTP handle is released.

`transport/connected?` requires OPEN and accepted admission. The client keeps
RAF/drawing but discards connected elapsed time and local action copies. A
transition between local and authoritative ownership resets fractional time.
The admitted display stays still until snapshots are introduced next.
