# Clojure Arena

A browser platform fighter built incrementally with ClojureScript and pure
shared Clojure data transformations. The target is two to four players with
three stocks and knockback percentages, without HP.

## Current scope

Checkpoints 01–51 complete (51/70). Fresh players have three stocks and explicit active/respawning/eliminated lifecycle validated in snapshots; stock consumption follows next.
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
For watched shared play use `http://<HOST>:8081/?server=http://<HOST>:8082`
with both `just dev <HOST>` and `just server <HOST> 8082` running.
`just check` runs JVM, Node and an isolated release build.

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
- [Multiplayer verification](docs/multiplayer-verification.md)

The game specification describes the full target; only the completed roadmap
checkpoints describe delivered behavior. Each checkpoint has one Conventional Commit.
