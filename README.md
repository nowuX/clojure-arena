# Clojure Arena

A browser platform fighter for two to four connected players, built with
Clojure, ClojureScript and immutable shared simulation. The core route
(checkpoints 01–70), ability intention capture (71) and Dash (72) are complete
(72/87). The remaining abilities and ledge recovery (checkpoints 73–87) are
planned and pending. Each checkpoint has one
Conventional Commit.

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
color. Shift performs a Dash: 80 px in 0.2 s with brief hit immunity and a
2.5 s wait. Q, E, R and F are captured and validated as intentions without
gameplay effects yet; they and ledge recovery are planned in checkpoints 73–87.

## Verify and inspect

`just check` runs JVM tests, Node browser doubles and an isolated release build.
Native appearance, focus and remote-device access require the manual checklists;
an automated pass does not establish those results.

- [Development and commands](docs/development.md)
- [Game specification and planned abilities](docs/game-specification.md)
- [Simulation](docs/simulation.md), [combat](docs/combat.md) and [matches](docs/matches.md)
- [Networking](docs/networking.md) and [architecture](docs/architecture.md)
- [Language manual in Spanish](docs/language-guide.md)
- [Local verification](docs/simulation-verification.md) and [multiplayer verification](docs/multiplayer-verification.md)
- [Monochrome design system](docs/design-system.md)
- [Implementation roadmap (72/87 complete)](docs/implementation-roadmap.md)

## License

MIT. See [LICENSE](LICENSE).

## Legal

Clojure Arena is an original, independent project. It is not affiliated with,
endorsed by or connected to Nintendo or any other publisher, and it ships no
third-party characters, artwork, music or other assets: every visual is
monochrome geometry drawn at runtime by the included code. All trademarks
remain the property of their owners and, where mentioned, are used only for
identification. See [NOTICE](NOTICE) for the full statement. The software is
provided "as is", without warranty of any kind, and the author accepts no
liability arising from its use.
