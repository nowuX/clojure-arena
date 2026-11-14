# Clojure Arena

A browser platform fighter built incrementally with ClojureScript and pure
shared Clojure data transformations. The target is two to four players with
three stocks and knockback percentages, without HP.

## Current scope

Checkpoints 01–39 complete (39/70). The local fighting loop is served by a pinned Clojure/http-kit/Ring HTTP entry point with explicit host and port, public assets, content types, 404 responses and GET /health. Browser compilation and HTTP delivery remain separate processes; gameplay is still local.
Future mechanics remain specified in the roadmap.

## Run

Requires Java 21+, Clojure CLI, Node 22+, pnpm 12.17.0, and just.

```sh
just setup
just dev
```

Open http://127.0.0.1:8081/. Use `just dev <TAILSCALE_IP>` to serve on
another interface. `just build` compiles an optimized browser bundle.
`just test` runs the Node simulation and browser-boundary tests.

To serve built assets with Clojure, run `just build`, then
`just server <HOST> 8082` and open `http://<HOST>:8082/`.
`just test-server` runs shared simulation and JVM server tests.

## Guides

- [Implementation roadmap](docs/implementation-roadmap.md)
- [Game specification](docs/game-specification.md)
- [Design system](docs/design-system.md)
- [Development](docs/development.md)
- [Networking](docs/networking.md)
- [Simulation](docs/simulation.md)
- [Local combat](docs/combat.md)
- [Architecture](docs/architecture.md)
- [Language guide (Spanish)](docs/language-guide.md)
- [Local verification](docs/simulation-verification.md)

The game specification describes the full target; only the completed roadmap
checkpoints describe delivered behavior. Each checkpoint has one Conventional Commit.
