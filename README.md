# Clojure Arena

A browser platform fighter built incrementally with ClojureScript and pure
shared Clojure data transformations. The target is two to four players with
three stocks and knockback percentages, without HP.

## Current scope

Checkpoints 01–36 complete (36/70). The local melee loop accumulates percentage exactly once per strike and displays player values. A pure configurable calculator defines K = 200 + 3P and summed directional impulses from final post-hit percentage; physical integration is pending.
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

## Guides

- [Implementation roadmap](docs/implementation-roadmap.md)
- [Game specification](docs/game-specification.md)
- [Design system](docs/design-system.md)
- [Development](docs/development.md)
- [Simulation](docs/simulation.md)
- [Local combat](docs/combat.md)
- [Architecture](docs/architecture.md)
- [Language guide (Spanish)](docs/language-guide.md)
- [Local verification](docs/simulation-verification.md)

The game specification describes the full target; only the completed roadmap
checkpoints describe delivered behavior. Each checkpoint has one Conventional Commit.
