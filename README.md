# Clojure Arena

A browser platform fighter for two to four connected players, built with
Clojure, ClojureScript and immutable shared simulation. The 70-checkpoint
implementation route is complete (70/70). Each checkpoint has one Conventional Commit.

## Play

Requires Java 21+, Clojure CLI, Node 22+, pnpm 12.17.0 and just.

```sh
just setup
just build
just server 127.0.0.1 8082
```

Open http://127.0.0.1:8082/ in two browser pages on the service host. Each
accepted connection owns a unique ID/color; a fifth is rejected. A single
connection waits for a second player. For Tailscale, watched development and
offline play, see [Development](docs/development.md).

A/D moves, fresh W presses jump and air-jump, S drops through a semisolid,
and a primary click inside the arena strikes. Wait for the first landing to
move. The neutral **Tú** marker identifies your player.

Matches use three stocks, accumulating knockback percentages, three-second
respawns, two-second protection, a three-minute clock and automatic rematches.
The server owns shared physics and results. Disconnected play uses a separate
local sandbox; the connection status shows mode, endpoint and known errors.
UI and platforms are monochrome; only player bodies and identity swatches use
color. Additional abilities remain explicitly outside the delivered route.

## Verify and inspect

`just check` runs JVM tests, Node browser doubles and an isolated release build.
Native appearance, focus and remote-device access require the manual checklists;
an automated pass does not establish those results.

- [Development and commands](docs/development.md)
- [Game specification and deferred abilities](docs/game-specification.md)
- [Simulation](docs/simulation.md), [combat](docs/combat.md) and [matches](docs/matches.md)
- [Networking](docs/networking.md) and [architecture](docs/architecture.md)
- [Language guide in Spanish](docs/language-guide.md)
- [Local verification](docs/simulation-verification.md) and [multiplayer verification](docs/multiplayer-verification.md)
- [Monochrome design system](docs/design-system.md)
- [Completed implementation roadmap](docs/implementation-roadmap.md)
