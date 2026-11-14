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
Node client tests remain `just test`. No WebSocket gameplay exists yet.
