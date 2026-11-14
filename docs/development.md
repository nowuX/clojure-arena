# Development

Requires Java 21+, Clojure CLI, Node 22+, pnpm 12.17.0, and just.
Dependency versions and the pnpm lockfile are pinned.

## Run the shared arena

```sh
just setup
just build
just server 127.0.0.1 8082
```

Open http://127.0.0.1:8082/ in each browser. Each accepted socket becomes a
separate player in that server's one room; at most four are admitted.
`GET /health` checks HTTP readiness; it does not prove the JS bundle exists or
that a browser has received an identity/snapshot.

For another device, get the service machine's address with `tailscale ip -4`,
run `just server <TAILSCALE_IP> 8082`, and open `http://<TAILSCALE_IP>:8082/`.
Binding and an HTTP request on the host do not establish remote access.

## Watch while using the game server

Run these in separate terminals, using the same host address:

```sh
just dev 127.0.0.1
just server 127.0.0.1 8082
```

Open `http://127.0.0.1:8081/?server=http://127.0.0.1:8082`.
For Tailscale, replace both host addresses with `<TAILSCALE_IP>`.
The watcher serves assets on 8081; the game server owns `/ws` on 8082. Plain
`:8081/` has no gameplay server and falls back locally. HTTP(S) in `server`
is translated to WS(S); do not put a `ws://` URL in that query parameter.

The JVM serves existing files and never compiles ClojureScript. An isolated
release does not replace `public/js`:

```sh
just build '{:output-dir "/tmp/clojure-arena-sequential-release/js"}'
```

## Verify

```sh
just test-server
just test
just check
```

`test-server` discovers JVM/shared tests, including real bounded HTTP/WebSocket
checks with ephemeral ports and owned cleanup. `test` compiles/runs Node tests
with EventTarget, RAF and DOM/Canvas doubles. `check` runs both suites and a
release in `/tmp/clojure-arena-sequential-check/js`; an optional directory
argument changes that isolated output. None of these starts a persistent dev
server or proves native Canvas pixels, focus or access from another device.

Use [Multiplayer verification](multiplayer-verification.md) for four browsers,
fifth-client rejection, reconnection and remote checks, or
[Simulation verification](simulation-verification.md) for the offline sandbox.
