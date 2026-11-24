# Working instructions

## Incremental delivery

- Follow `docs/implementation-roadmap.md` in numeric order. Begin with the first
  pending checkpoint. Do not skip dependencies or introduce later mechanics.
- Implement one checkpoint per delivery unless the user explicitly requests a
  range. Complete integration and relevant verification before moving on.
- Keep each checkpoint minimal and runnable at its stated level. A buildable
  scene may intentionally lack collisions, attacks, networking, or match rules
  until their respective checkpoints.
- After completing and verifying an implementation checkpoint, create exactly
  one Conventional Commit using its documented subject. Stage only that
  checkpoint's changes. Honor any explicit instruction to withhold commits.
- Report actual checks, blockers, and unverified behavior. Do not mark a
  checkpoint complete merely because code was written.
- Keep README's project status accurate. Mark each delivered checkpoint complete
  in the roadmap in the same commit; Git is the implementation history. Do not
  add changelogs, dated delivery reports, or per-checkpoint log files.

## Code and boundaries

- Use small named functions and plain immutable data. Shared `.cljc` simulation
  functions receive values and return values; browser/server boundaries own
  side effects. Introduce files only when they have an implemented role.
- Keep Canvas/DOM/events in ClojureScript. Use one Clojure room agent for trusted
  join, input, tick, and leave messages once networking is introduced.
- Use `just` for recurring commands. Define a recipe only when its underlying
  command exists. Pin dependencies and commit lockfiles when introduced.
- Add meaningful tests for timing, input lifetimes, physics, concurrency, and
  protocol boundaries. Avoid tests that merely restate drawing or configuration.
- Keep existing user work and running services intact. Reuse authorization;
  clarify only material missing decisions. Use isolated build output while a
  watcher is serving assets.

## Documentation and appearance

- Communicate with the user in Spanish. New identifiers, comments, and technical
  documentation use English. Product text and the Clojure/ClojureScript language
  manual use Spanish.
- Follow `docs/design-system.md` from the first rendered page: neutral grayscale
  UI, arena, platforms, labels, and states. Color is reserved for player bodies
  and the small identity swatches that identify them.
- Keep README short, with the actual start commands and a guide index. Add
  implementation explanations only when their code exists. The roadmap and
  specification may describe future work explicitly as planned.
- A passing build, Node test double, or same-host HTTP check does not establish
  native browser behavior or access from another device. State where each check
  ran. Use the service machine's actual Tailscale address for remote access.

## Scope

Implement the specified browser game only (core checkpoints 01–70 and planned
abilities/recovery checkpoints 71–87). Keep automated external opponents,
training systems, character-specific stats, extra victory scoring, art assets,
touch controls, and mechanics outside the roadmap out of scope.
