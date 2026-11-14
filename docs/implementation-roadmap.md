# Implementation roadmap

## Status and scope

**65 of 70 checkpoints complete.** Checkpoints 01–65 are complete; the remaining checkpoints are pending. The target is a browser platform fighter with a local sandbox and one
shared authoritative room for two to four connections. Read the
[game specification](game-specification.md), [design system](design-system.md),
and [working instructions](../AGENTS.md) before implementing checkpoint 01.

The sequence is linear and its IDs are the canonical references. Milestones
are ordered by dependencies: base physics, platforms, players, local combat,
network authority, stocks, matches, then polish. Every state introduces only
the behavior described by its checkpoint; later features are intentionally absent.

## Delivery contract

- Implement checkpoints in order. One completed checkpoint produces one
  Conventional Commit with the subject listed under that checkpoint. If the
  user requests a range, preserve separate commits within it.
- Check each step's result and preserve accepted earlier behavior. Update its
  status and README's current scope in that same commit. Do not claim a feature
  complete solely because the final design describes it.
- Implement the smallest understandable version. Avoid frameworks, generic
  architecture, premature modules, or future state fields without a current role.
- Use pure shared functions for game rules and small effectful browser/server
  boundaries. Introduce meaningful tests at timing, collision, protocol, and
  concurrency boundaries; no test is needed just to mirror a rectangle draw.
- Introduce the command runner at 01. Define test at 09, server/test-server at
  39, and check at 49 when their tools and runners exist. Before check exists,
  use the implemented suite/build recipes directly.
- All UI and non-player world colors follow the monochrome design system from
  01 onward. Checkpoint 69 refines layout/identity; it does not defer that rule.
- Documentation grows with actual code. Add the Spanish language manual at 15,
  update it when new concepts appear, and add the concurrency diagram at 39.
  Keep reference docs topical and README short from the outset.
- Test against the current stage, not future behavior. In particular, active
  admitted players at 42 become waiting records only when match phases arrive
  at 57; factory resets gain stocks/protection only when those fields exist.
- Use isolated release output while a watcher is serving assets. Do not start,
  stop, or replace a user's existing service just to run checks. State where HTTP,
  native-browser, and remote-device checks ran; one does not prove the others.
- Git records the implementation history. Do not add changelog documents,
  checkpoint delivery logs, synthetic authorship, or fabricated test evidence.

## Planned tooling and documentation

| Item | Introduction | Responsibility |
| --- | --- | --- |
| package.json, pnpm-lock.yaml, shadow-cljs.edn, justfile | 01 | Pinned browser build, setup/watch/release |
| public/index.html and client.cljs | 01–04 | Startup, Canvas, and browser effects |
| game.cljc | 05 | Immutable world and pure simulation |
| test/ and just test | 09 | Shared simulation and client regressions in Node |
| docs/development.md | 01 | Working commands, prerequisites, build/reload boundaries |
| docs/simulation.md and docs/simulation-verification.md | 15 | Implemented rules/data flow and local checks |
| docs/language-guide.md | 15 onward | Spanish Clojure/shared section and ClojureScript section |
| docs/architecture.md | 15 onward | Current ownership, principal functions; server/concurrency diagram from 39 |
| deps.edn, server.clj, and just server/test-server | 39 | JVM HTTP/WebSocket entry point and real-boundary checks |
| protocol.cljc, transport.cljs | 40 | Shared EDN contract and client transport lifecycle |
| room.clj | 41 | Agent-owned membership, inputs, world, and tick |
| ticker.clj / publication.clj / heartbeat.clj | 44 / 45 / 47 | Bounded ticking, snapshot delivery, connection liveness |
| docs/multiplayer-verification.md and just check | 49 | Four-browser lifecycle, same-tick state, and integrated checks |
| match.cljc | 57 | Pure participation, phases, results, and rematches |

These files are planned destinations, not an instruction to create placeholders.
Use the namespace `clojure-arena` and paths under `src/clojure_arena` and
`test/clojure_arena` when code is introduced. Other small files are appropriate
only when they own an implemented responsibility.

## Milestone index

| Milestone | Checkpoints | Completion target |
| --- | --- | --- |
| 1 | 01–21 | One controllable jumping character and solid platform |
| 2 | 22–27 | Four platforms, rounded semisolid contacts, and drop-through |
| 3 | 28–30 | Independent player records with intention-based facing |
| 4 | 31–38 | Local melee, percentages, and physical knockback |
| 5 | 39–49 | Four shared connections with authoritative simulation |
| 6 | 50–56 | Stocks, fresh lives, and bounded protection |
| 7 | 57–66 | Countdown, contenders, clock, results, and rematches |
| 8 | 67–70 | Clear diagnostics, efficient panel, responsive monochrome UI, concise guides |

## Milestone 1 — A moving character on one platform

Checkpoints **01–21**. Introduce rendering, immutable data, input, fixed time, contacts, acceleration, jumps, and spawn eligibility in small steps.

### 01 — Boot the smallest ClojureScript page

- **Status:** Complete.
- **Implement / result:** Check Java 21+, Clojure CLI, Node 22+, pnpm, and just. Pin build
  dependencies and the package manager, commit the lockfile, configure one Shadow browser
  target, and call a minimal client init from HTML. Add only working setup/dev/build
  recipes and a Spanish ready message. Follow the neutral palette from the first page.
- **Verify:** Install from the lockfile, compile a release, and open the served page.
  Confirm the ready text and absence of startup errors if browser verification is
  available. Record the advertised host/port and the check location.
- **Explain:** HTML loads generated JavaScript; init owns browser startup.
- **Commit:** `feat(build): bootstrap the browser application`.

### 02 — Draw an empty arena

- **Status:** Complete.
- **Implement / result:** Add one 800 × 480 Canvas and fill it once with the neutral
  background. Keep the page free of gameplay objects.
- **Verify:** Check backing dimensions and a repeatable initial draw. Do not infer browser
  rendering from compilation alone.
- **Explain:** Canvas context, logical coordinates, and downward-positive Y.
- **Commit:** `feat(render): draw the empty arena`.

### 03 — Draw one platform

- **Status:** Complete.
- **Implement / result:** Draw one neutral filled rectangle at (170, 280), size 460 × 16.
  Keep collision and scene collections out of this step.
- **Verify:** Inspect the rectangle position and dimensions; reload reconstructs it.
- **Explain:** Rectangle top-left coordinates become one drawing call.
- **Commit:** `feat(render): draw the main platform`.

### 04 — Draw one character

- **Status:** Complete.
- **Implement / result:** Draw a cyan circle of radius 16 centered at (400, 264). Keep
  player color limited to the body.
- **Verify:** Its feet touch Y = 280; no movement or collision code is introduced.
- **Explain:** A circle uses a center and radius, unlike a rectangle origin.
- **Commit:** `feat(render): draw the first character`.

### 05 — Represent the scene as immutable data

- **Status:** Complete.
- **Implement / result:** Introduce game.cljc with the current platform and player data,
  and a client atom for the displayed world. Rendering reads geometry from values rather
  than repeating it in drawing functions.
- **Verify:** Changing initial X changes the drawing without editing draw-player. Preserve
  scene appearance and supplied immutable values.
- **Explain:** Immutable game data and the mutable browser reference have separate roles.
- **Commit:** `refactor(game): represent the scene as immutable data`.

### 06 — Define the pure simulation boundary

- **Status:** Complete.
- **Implement / result:** Add step with explicit world, inputs, and dt arguments. Return
  the unchanged world initially, without browser APIs or side effects.
- **Verify:** Call step with ordinary values and confirm the input world is preserved and
  no environment is required.
- **Explain:** The simulation will return a next world; callers store it.
- **Commit:** `refactor(game): define the pure simulation boundary`.

### 07 — Connect rendering and fixed simulation time

- **Status:** Complete.
- **Implement / result:** Use RAF for drawing and an elapsed-time accumulator for
  0.025-second simulation steps. Count at most 100 ms per frame, carry the remainder, and
  implement stop/init cleanup for one RAF loop.
- **Verify:** A controlled 100 ms interval produces four ticks. Subtick time carries; long
  pauses are bounded; repeated init leaves one animation loop.
- **Explain:** Drawing frequency and fixed simulation time are independent.
- **Commit:** `feat(client): advance fixed simulation ticks`.

### 08 — Read horizontal intentions

- **Status:** Complete.
- **Implement / result:** Capture A/D keydown/keyup and expose -1, 0, or 1. Both held keys
  mean neutral. Clear keys on blur/stop/restart, with removable listener references.
- **Verify:** Press, hold, release, both directions, blur, and repeated initialization.
  Input is inspectable without production log spam; position remains unchanged.
- **Explain:** Events update input data rather than moving a player.
- **Commit:** `feat(input): capture horizontal intentions`.

### 09 — Move horizontally at constant speed

- **Status:** Complete.
- **Implement / result:** Use the horizontal intention in step to set X velocity to ±220
  units/s and integrate X. Neutral stops immediately at this intentionally simple stage.
  Add meaningful simulation tests and the just test recipe with its runner.
- **Verify:** One 25 ms tick moves 5.5 units; forty move 220 in free space. Test
  left/right/neutral, input immutability, and equal fixed ticks across render cadences.
- **Explain:** Intention becomes velocity, then displacement in the returned world.
- **Commit:** `feat(movement): integrate constant horizontal speed`.

### 10 — Apply gravity

- **Status:** Complete.
- **Implement / result:** Add vertical velocity and 980 units/s² gravity, then integrate Y
  using semi-implicit Euler. Move the falling spawn to (400, 160). Passing through the
  platform is expected until the next checkpoint.
- **Verify:** Each 25 ms tick adds 24.5 units/s to Y velocity. The first ends at Y =
  160.6125. Horizontal input still works; reload resets the falling scene.
- **Explain:** Gravity changes velocity before velocity changes position.
- **Commit:** `feat(physics): apply gravity`.

### 11 — Land on the platform

- **Status:** Complete.
- **Implement / result:** Detect a descending circle crossing the flat top with supported
  horizontal geometry. Correct feet to the top and downward velocity to zero; maintain
  support while standing and release it at edges.
- **Verify:** Landing, repeated standing ticks, walking off either edge, fast downward
  crossing, and falling beside the platform. Do not extend flat support by the circle
  bounding box.
- **Explain:** Contact detection and position/velocity response are separate.
- **Commit:** `feat(collision): land on the main platform`.

### 12 — Complete solid-platform contacts

- **Status:** Complete.
- **Implement / result:** Add swept side, underside, and rounded-corner contacts for a
  circle against the solid rectangle, including overlap correction. Remove inward normal
  velocity and continue remaining movement with a four-contact limit and 1e-9 tolerance.
- **Verify:** Use controlled approaches to all faces/corners and high speeds. Separating
  velocity remains; corner tangential motion survives; the contact budget terminates
  narrow-gap cases.
- **Explain:** The contact normal selects the motion component to remove.
- **Commit:** `feat(collision): resolve solid faces and corners`.

### 13 — Add horizontal acceleration

- **Status:** Complete.
- **Implement / result:** Accelerate voluntary X toward the ±220 target by at most 880 ×
  dt, without overshoot. Keep immediate neutral stopping until the separate friction step.
- **Verify:** At 25 ms, speed changes by 22 units/s and reaches 220 from rest in ten
  ticks. Reversal, clamping, gravity, and collisions remain consistent.
- **Explain:** Target speed differs from acceleration toward it.
- **Commit:** `feat(movement): accelerate toward the requested speed`.

### 14 — Add horizontal friction

- **Status:** Complete.
- **Implement / result:** With neutral intention, move X velocity toward zero at 1100
  units/s² without sign reversal. Use the same rule on ground and in air.
- **Verify:** At 25 ms, friction removes 27.5 units/s. From 220 it stops in eight ticks
  and travels 19.25 more units in free space. Both keys and blur use neutral braking.
- **Explain:** Acceleration under intention and braking without intention are separate
  rules.
- **Commit:** `feat(movement): brake neutral horizontal motion`.

### 15 — Verify horizontal movement and explain the language basics

- **Status:** Complete.
- **Implement / result:** Consolidate movement/collision regressions, actual setup
  commands, and a short local checklist. Introduce simulation/architecture explanations
  and a concise Spanish language-guide.md split into Clojure/shared concepts and
  ClojureScript browser concepts.
- **Verify:** Run meaningful Node tests and an isolated release build. Check listener/RAF
  cleanup, movement, focus loss, and contact cases. Verify documentation links and
  examples against existing functions.
- **Explain:** Trace one key event through inputs, step, immutable data, and drawing.
- **Commit:** `test(movement): verify horizontal movement and contacts`.

### 16 — Capture one jump request per press

- **Status:** Complete.
- **Implement / result:** Track fresh W presses separately from held movement. Keep a
  short tap until a tick consumes it; ignore autorepeat and clear requests on
  blur/stop/restart. Reading inputs does not consume them.
- **Verify:** Press/release between ticks, held W, repeated keydown, and four ticks in one
  frame. Only the first tick sees the pending action; no physical jump exists yet.
- **Explain:** Held intentions and one-shot actions have different lifetimes.
- **Commit:** `feat(input): capture one jump request per press`.

### 17 — Identify support and lock initial spawn controls

- **Status:** Complete.
- **Implement / result:** Expose grounded/support queries from flat-top geometry. Add a
  spawn-locked flag: neutral voluntary movement and reject actions until a supported
  landing; side/underside/corner contact cannot unlock it. Keep gravity/contact processing
  active.
- **Verify:** Initial fall stays at X = 400 even with D/W held. Top landing unlocks; held
  D works the following tick. Corner/side/underside contacts stay locked, later falls do
  not relock, and old W requests do not replay.
- **Explain:** Support is physical state, and spawn eligibility belongs in simulation.
- **Commit:** `feat(spawn): unlock controls after supported landing`.

### 18 — Apply a ground jump

- **Status:** Complete.
- **Implement / result:** Accept a consumed jump only with unlocked controls and support
  at the tick start. Set Y velocity to -420, then apply gravity and movement. Reject
  airborne requests without buffering for a later landing.
- **Verify:** From Y = 264, the first tick ends at Y = 254.1125 with vy = -395.5. Test
  moving launch, hold/release, landing then fresh jump, and ascending underside contact.
- **Explain:** Jump changes velocity while ordinary physics continues.
- **Commit:** `feat(jump): launch from ground support`.

### 19 — Track air-jump availability

- **Status:** Complete.
- **Implement / result:** Store one available air jump in player data. Initialize it to
  one and restore it only on a supported top landing. A ground jump or walking off an edge
  preserves it; activation follows next.
- **Verify:** Initial fall, ground launch, edge departure, and constructed exhausted
  state. Supported landing restores one; side/underside/corner contact does not.
- **Explain:** Jump allowance is independent of pending input and support.
- **Commit:** `feat(jump): track the air-jump allowance`.

### 20 — Apply one air jump

- **Status:** Complete.
- **Implement / result:** Accept a fresh airborne W request only while unlocked and with
  allowance remaining. Replace total Y velocity with -420 and spend the allowance before
  gravity and integration.
- **Verify:** Ground jump plus air jump succeeds; a third fails. Test ascent/apex/descent,
  edge departure, landing recovery, and held W. Horizontal rules remain active.
- **Explain:** Ground and air launches share velocity but consume different eligibility.
- **Commit:** `feat(jump): apply one air jump`.

### 21 — Verify the first playable platform loop

- **Status:** Complete.
- **Implement / result:** Integrate movement, spawn lock, support, both jumps, timing, and
  restart behavior. Extend the simulation guide, Spanish language manual, and local
  verification checklist using only implemented concepts.
- **Verify:** Run Node tests and an isolated build. Compare equal per-tick input sequences
  across frame cadences; verify pending actions on focus loss and repeated init/stop.
  Record native browser behavior separately.
- **Explain:** One jump travels from browser capture through eligibility to the drawn
  world.
- **Commit:** `test(game): verify movement and double jumps`.

## Milestone 2 — Multiple platforms and drop-through

Checkpoints **22–27**. Extend the scene while preserving existing movement, support, and jump rules.

### 22 — Store a collection of platforms

- **Status:** Complete.
- **Implement / result:** Replace the single platform with a vector of stable ID/type
  entries. Begin with the existing main solid and adapt drawing/support/collision callers
  without changing geometry.
- **Verify:** Draw the main platform once and pass all earlier movement/jump/contact
  checks through the collection boundary.
- **Explain:** Scene collections carry data instead of duplicated renderer geometry.
- **Commit:** `refactor(scene): store platforms by stable identity`.

### 23 — Resolve contacts across multiple solids

- **Status:** Complete.
- **Implement / result:** Find the earliest eligible contact across all platforms, break
  equal times by stable ID, and continue remaining movement with the shared contact
  budget. Any flat top may support a jump.
- **Verify:** Construct two-solid fast crossings, reversed collection order, non-first
  support, coincident endpoints, and landing followed by wall contact in one tick.
- **Explain:** Choose a scene contact before applying one physical response.
- **Commit:** `feat(collision): resolve contacts across multiple solids`.

### 24 — Draw three semisolid platforms

- **Status:** Complete.
- **Implement / result:** Add left/right/upper scene entries using the specification
  table. Draw all three with neutral outlines; distinguish type by fill versus outline,
  never hue. Keep them physically traversable for now.
- **Verify:** Confirm stable geometry on reload, all four entries drawn once, and
  unchanged main-platform response.
- **Explain:** A visible scene entry can precede its collision rule.
- **Commit:** `feat(scene): draw the semisolid platforms`.

### 25 — Land on semisolid tops and rounded endpoints

- **Status:** Complete.
- **Implement / result:** Filter descending swept contacts to tops and upper endpoint arcs
  while preserving upward and side/underside traversal. Flat support requires center X
  inside the inclusive span. Slide around upper corners on departure without rectangle
  penetration.
- **Verify:** Test rising through then landing, fast falls, contact-time X, both slow/fast
  edge departures, upper endpoint overlap, multiple platforms, and no air-jump/spawn
  unlock from corner-only contact.
- **Explain:** One-way platforms filter eligible surfaces rather than adding side walls.
- **Commit:** `feat(collision): land on semisolid tops`.

### 26 — Capture one drop request from S

- **Status:** Complete.
- **Implement / result:** Apply the jump-request lifecycle to fresh S presses, exposing an
  independent drop flag. Do not change physics yet.
- **Verify:** Short taps, autorepeat, held S, four ticks per frame, blur/restart, and
  simultaneous W/A/D. Consumption clears only one-shot requests.
- **Explain:** Input capture is independent of the action eligibility rule.
- **Commit:** `feat(input): capture one drop request per press`.

### 27 — Drop through the supporting semisolid

- **Status:** Complete.
- **Implement / result:** When unlocked and supported by a semisolid, record its ID as a
  per-player contact exception. Let gravity lower the body without teleport/extra impulse.
  Drop beats simultaneous jump there and preserves the air jump. Clear the exception after
  full passage or feet returning above the top.
- **Verify:** Drop from every semisolid, reject S on solid/in air, preserve other
  platforms, and jump back during partial traversal then land again. Missing platform IDs
  clear safely.
- **Explain:** A temporary exception affects only one player/platform pair.
- **Commit:** `feat(platform): drop through the supporting semisolid`.

## Milestone 3 — Independent players and facing

Checkpoints **28–30**. Move identity and per-player state into shared immutable data before adding combat.

### 28 — Store and draw players by ID

- **Status:** Complete.
- **Implement / result:** Introduce a player factory and an ID-keyed map of one to four
  fixtures using cyan/crimson/amber/green and the specified local positions. Each record
  owns fresh physical/input eligibility state; rendering remains read-only.
- **Verify:** Draw one to four bodies with distinct IDs/colors and separate state. Factory
  construction is immutable; initial velocities, facing defaults when introduced, jumps,
  and locks are independent.
- **Explain:** Map keys define identity independently of color and iteration order.
- **Commit:** `refactor(players): store characters by stable identity`.

### 29 — Route inputs and advance players independently

- **Status:** Complete.
- **Implement / result:** Give step an inputs-by-player map. Step every player once with
  shared platforms and dt; missing input is neutral, and unknown IDs do not create bodies.
  The local page explicitly controls only player-1.
- **Verify:** Different simultaneous inputs, neutral fixtures, map-order independence,
  independent spawn locks/jumps/drop exceptions, and a missing player-1.
- **Explain:** One shared world can contain independently controlled immutable records.
- **Commit:** `feat(players): route inputs by player identity`.

### 30 — Remember facing from accepted intention

- **Status:** Complete.
- **Implement / result:** Add left/right facing, initially right. Unlocked
  negative/positive A/D updates it; neutral, blur, braking, falling, contacts, and
  external velocity preserve it. Draw a neutral white facing dot.
- **Verify:** Reverse intention while velocity still points the other way. Test locked
  spawn, both keys, independent players, and velocity-only changes.
- **Explain:** Facing remembers intention rather than deriving from velocity.
- **Commit:** `feat(players): remember accepted facing direction`.

## Milestone 4 — Local melee and knockback

Checkpoints **31–38**. Accept input, select targets, resolve percentage once, and integrate physical impulses before introducing transport.

### 31 — Capture one melee request from primary click

- **Status:** Complete.
- **Implement / result:** Listen for primary pointer presses only on the arena canvas.
  Queue one attack action with the same consume/blur/stop/restart lifecycle as W/S;
  holding does not repeat. No hit effect exists yet.
- **Verify:** Short clicks, duplicate/coalesced presses, secondary buttons, outside-canvas
  clicks, canvas replacement, and four ticks in one frame.
- **Explain:** Pointer input supplies an intention rather than a hit result.
- **Commit:** `feat(input): capture primary-pointer melee requests`.

### 32 — Accept strikes with a cooldown

- **Status:** Complete.
- **Implement / result:** Add per-player 0.25-second simulation cooldown and accepted
  strike sequence. Reject clicks while locked/cooling down without delayed replay; use
  tolerance at expiry. Preserve unrelated controls.
- **Verify:** Exact ten-tick expiry, early rejected clicks, repeated presses, no holding
  repeat, misses still accepted at this stage, and independent attackers.
- **Explain:** Acceptance is a state transition distinct from finding a target.
- **Commit:** `feat(melee): accept strikes with independent cooldowns`.

### 33 — Select targets in the forward strike region

- **Status:** Complete.
- **Implement / result:** Query the 60 px forward half-disk once on each acceptance tick
  after all movement/contacts, using one final player snapshot. Exclude attacker ID and
  the vertical center line, include the curved boundary, and do not occlude with
  platforms.
- **Verify:** Both facings, exact reach, vertical offsets, multiple targets, misses, no
  later-tick query, simultaneous attackers, and map-order independence.
- **Explain:** Geometric targeting reads a shared snapshot without changing it.
- **Commit:** `feat(melee): select targets in the forward region`.

### 34 — Accumulate percentage and show the player panel

- **Status:** Complete.
- **Implement / result:** Add independent accumulated percentage, initially zero, and +10
  points for each selected hit without a 100 cap. Render ID/swatch/value beneath the arena
  using the same frame world. All panel surfaces/text stay neutral.
- **Verify:** 40 to 50, simultaneous hits to one target, unrelated records, values over
  100, and panel correspondence. Percentage does not change normal movement speed.
- **Explain:** Hit vulnerability is data separate from health, position, and velocity.
- **Commit:** `feat(melee): accumulate and display hit percentage`.

### 35 — Resolve each strike only once

- **Status:** Complete.
- **Implement / result:** Track the last resolved strike per attacker and consume it
  atomically with its effects, including misses. Replaying a returned target set or
  entering reach later cannot create another hit.
- **Verify:** Repeated resolution is idempotent; separate attackers may both resolve
  strike 1; new accepted strikes can hit again; an old miss stays consumed.
- **Explain:** A per-attacker sequence guards exactly-once gameplay effects.
- **Commit:** `fix(melee): resolve each accepted strike once`.

### 36 — Define the trial knockback calculation

- **Status:** Complete.
- **Implement / result:** Add the pure K = 200 + 3P calculation with configurable named
  defaults. Use the final post-hit percentage for every hit that tick; sum left/right axis
  contributions without averaging or applying voluntary caps. Do not integrate impulses
  yet.
- **Verify:** One right hit at 10 gives (184, -138); two opposing hits at 20 give (0,
  -312). Test no hits, scaling beyond 100, explicit configuration, and order independence.
- **Explain:** Impulse is a velocity change, not displacement or movement speed.
- **Commit:** `feat(knockback): calculate percentage-scaled impulses`.

### 37 — Integrate impulses with movement and contacts

- **Status:** Complete.
- **Implement / result:** Apply grouped hit impulses after movement/target selection to
  total and retained velocity. Track received X/Y; damp received X independently of
  voluntary A/D, project both vectors at contacts, and let valid jumps replace Y while
  preserving received X.
- **Verify:** Actual hits then following ticks, total speed over 220, neutral/held
  braking, walls/corners, rising through semisolids, landing, ground/air jumps,
  simultaneous impulses, and no duplicate resolution.
- **Explain:** Separate voluntary and received motion without advancing physics twice.
- **Commit:** `feat(knockback): integrate received motion with physics`.

### 38 — Verify local platforms, independence, and combat

- **Status:** Complete.
- **Implement / result:** Consolidate scene/jump/drop/facing/melee/knockback integration
  and explain the final local data flow. Keep disconnected fixtures separate from future
  network identities. Extend existing guides rather than adding delivery logs.
- **Verify:** Run Node tests and an isolated build. Combine multiple actions in a tick,
  verify contacts after a real hit, and exercise actual local browser input when
  available.
- **Explain:** One coherent local simulation is ready to be owned by a server.
- **Commit:** `test(combat): verify the local fighting loop`.

## Milestone 5 — One authoritative shared arena

Checkpoints **39–49**. Add HTTP/WebSocket, exact EDN messages, connection-owned identities/input, one room clock, snapshots, and cleanup.

### 39 — Boot the Clojure HTTP server

- **Status:** Complete.
- **Implement / result:** Pin Clojure/http-kit/Ring dependencies and add a JVM entry point
  with explicit host/port. Serve existing public assets, content types, missing-file 404s,
  and GET /health. Add working server/test-server just recipes and the architecture
  ownership diagram.
- **Verify:** Check readiness and existing JS over real HTTP, invalid options,
  stop/rebind, and address binding. HTTP readiness does not prove built assets or a
  gameplay connection.
- **Explain:** Serving assets and compiling them are separate processes.
- **Commit:** `feat(server): serve the browser assets over HTTP`.

### 40 — Exchange validated EDN over WebSocket

- **Status:** Complete.
- **Implement / result:** Upgrade /ws, send ready, accept an exact diagnostic ping, and
  reply pong. Decode exactly one EDN value without evaluating code; distinguish invalid
  EDN, invalid shape, and unsupported type. Derive client endpoint from origin or HTTP(S)
  server query.
- **Verify:** Real socket handshake, 426 without upgrade, repeated/fragmented frames,
  malformed then valid messages, unsupported internal commands, binary rejection, and
  client cleanup. Keep offline fallback playable.
- **Explain:** A typed message contract is distinct from transport connection state.
- **Commit:** `feat(protocol): exchange validated EDN over WebSocket`.

### 41 — Admit at most four unique identities

- **Status:** Complete.
- **Implement / result:** Introduce one room agent and a pure transition envelope.
  Serialize trusted joins, allocate a monotonic player ID and free palette keyword, bind
  them to a private connection token, and return captured post-commit identity/full
  effects.
- **Verify:** Concurrent excess admissions, four unique colors, monotonic IDs, duplicate
  internal joins, rejected unchanged state, separate servers, and callbacks observing
  committed room data. Browsers cannot choose identity.
- **Explain:** Capacity and allocation belong to one atomic serialized decision.
- **Commit:** `feat(room): admit four unique connection identities`.

### 42 — Spawn a player for each accepted connection

- **Status:** Complete.
- **Implement / result:** Use color spawn configuration and the shared factory to insert
  one player and neutral input atomically with admission. Start the server room without
  offline fixtures. Until match phases exist, admitted players are active; do not
  introduce future waiting state early.
- **Verify:** Membership/player/input consistency, four landings, independent body/combat
  state, EDN-safe world data, duplicate admission preservation, and capacity rejection
  without spawn.
- **Explain:** Transport membership and serializable gameplay world have separate
  ownership.
- **Commit:** `feat(room): spawn accepted player identities`.

### 43 — Route connection-owned input

- **Status:** Complete.
- **Implement / result:** Send fresh browser intentions immediately, independently of RAF,
  with exact horizontal/jump/drop/attack fields. Attach the private token on the server;
  merge latest direction and pending one-shot actions for that owner only. Clear
  per-session buffers on closure/replacement.
- **Verify:** Real two-socket ownership, spoofed IDs/tokens rejected, tap then neutral
  preserving an action, autorepeat, blur without RAF, pre-admission coalescing, and no
  session replay. Input alone never advances the world.
- **Explain:** The connection selects its owner; a wire payload cannot.
- **Commit:** `feat(input): route connection-owned intentions`.

### 44 — Advance one authoritative room at 40 Hz

- **Status:** Complete.
- **Implement / result:** Serialize trusted 25 ms ticks with joins/inputs. Step the room
  world once, increment room tick, consume one-shot flags, and retain horizontal
  intention. Use at most one queued tick; drop overload pulses without catch-up. An
  admitted OPEN browser discards local elapsed time/actions and stops local physics.
- **Verify:** Exact dt and tick count, actions consumed once, slow-room bounds, single
  clock for four connections, empty-room ticking, startup/stop cleanup, and no
  connected-time replay into offline fallback.
- **Explain:** One authority owns simulation time; rendering remains a client concern.
- **Commit:** `feat(room): advance the authoritative simulation clock`.

### 45 — Publish and render committed snapshots

- **Status:** Complete.
- **Implement / result:** Capture recipients/message from the same committed tick.
  Validate exact world fields and finite values. Publish through a bounded latest-only
  worker, encode once, and deliver off the physics queue. Browser accepts only newer ticks
  for the current admitted socket with its assigned ID present.
- **Verify:** Same-tick worlds match across real sockets; slow delivery does not block
  ticks. Reject malformed/stale/equal/session-old snapshots, render canvas/panel from one
  value, and restore a separate offline world after disconnect.
- **Explain:** Captured committed values cross an asynchronous boundary without rereading
  state.
- **Commit:** `feat(snapshot): publish and render the shared world`.

### 46 — Show a persistent full-room rejection

- **Status:** Complete.
- **Implement / result:** For a fifth connection, preserve authoritative capacity
  rejection and show a Spanish explanation. Clear identity/snapshot/input, close the
  unaccepted socket, and protect the terminal state from late callbacks while local
  fallback remains usable.
- **Verify:** Four accepted players continue unchanged. Rejected clients receive no
  identity/snapshot; late open/message/error/close cannot erase rejection. Explicit retry
  starts clean.
- **Explain:** A transport rejection must not mutate accepted gameplay ownership.
- **Commit:** `feat(connection): explain full-room rejection`.

### 47 — Clean up closed and expired sessions

- **Status:** Complete.
- **Implement / result:** Retire a token once and queue leave to remove
  member/player/input and free color. Add separate control Ping/Pong heartbeat with
  5-second pulses, 15-second monotonic deadline, and matching challenges. Timeout
  best-effort notifies the client, which preserves the terminal notice and falls back
  locally.
- **Verify:** Close/duplicate cleanup, token isolation after reuse,
  matching/mismatched/late pong deadlines, actual socket departure, server shutdown,
  full-room immunity, and silent device loss if available.
- **Explain:** Connection liveness and player simulation time use different clocks.
- **Commit:** `feat(connection): release closed and expired sessions`.

### 48 — Treat reloads and tabs as fresh sessions

- **Status:** Complete.
- **Implement / result:** Discard old client identity/pending input/snapshot tick on
  replacement and bind callbacks to their socket. Cookies, tabs, reloads, and reused
  colors cannot restore a retired player. Use ordinary admission after cleanup; there is
  no auto-retry.
- **Verify:** Same-cookie real sockets, stale callbacks/tokens, four-slot rejection during
  delayed cleanup, fresh neutral body state, larger IDs after reuse, and monotonic room
  tick.
- **Explain:** A network session is a connection lifetime, not a cookie or color.
- **Commit:** `fix(session): isolate reloads and replacement connections`.

### 49 — Verify the shared connection lifecycle

- **Status:** Complete.
- **Implement / result:** Consolidate real-agent and socket lifecycle coverage with a
  four-browser checklist. Reconcile architecture function contracts, wire messages,
  concurrency diagram, development URLs, and startup commands. Add just check for JVM/Node
  plus an isolated release build.
- **Verify:** Concurrent join/input/tick/leave, four accepted pages and a fifth rejection,
  same-tick melee/motion, closure/color reuse, reload/reconnect, and service cleanup.
  Distinguish native-browser checks from Node doubles and remote-device access.
- **Explain:** Trace admission through owned input, committed physics, publication, and
  leave.
- **Commit:** `test(multiplayer): verify the shared connection lifecycle`.

## Milestone 6 — Stocks, respawn, and protection

Checkpoints **50–56**. Introduce each lifecycle responsibility separately; no HP is added.

### 50 — Detect complete exits from blast bounds

- **Status:** Complete.
- **Implement / result:** Add the specified bounds as world data and a pure whole-circle
  exit query, without consuming stocks. Extend snapshot validation for exact finite
  noninverted bounds.
- **Verify:** All four edges, exact touch, partial exit, high speed, and ordinary movement
  outside the visible canvas but inside bounds.
- **Explain:** A gameplay loss boundary differs from platform contact geometry.
- **Commit:** `feat(arena): detect complete blast-bound exits`.

### 51 — Represent stocks and lifecycle

- **Status:** Complete.
- **Implement / result:** Add three stocks and active/respawning/eliminated lifecycle to
  fresh players and exact snapshot validation. Keep percentage and motion separate. Do not
  consume a stock yet.
- **Verify:** Factory independence, EDN roundtrip, legal lifecycle values, and rejection
  of negative/fractional/excess stocks.
- **Explain:** Stocks are remaining opportunities; percentage is hit vulnerability.
- **Commit:** `feat(stocks): represent player opportunities and lifecycle`.

### 52 — Consume one stock per ring-out

- **Status:** Complete.
- **Implement / result:** After moving all active bodies, resolve their exits before melee
  targeting. Decrement once, clear movement/hit effects, hide and disable inactive bodies,
  and enter respawning or eliminated. Discard inactive one-shot inputs; stock loss does
  not release membership.
- **Verify:** Successive losses via controlled reactivation fixtures, repeated inactive
  ticks, simultaneous exits, no hits from/against absent bodies, and unchanged other
  players.
- **Explain:** Lifecycle makes one fall charge one stock rather than every subsequent
  tick.
- **Commit:** `feat(stocks): consume one opportunity per ring-out`.

### 53 — Display stocks and lifecycle

- **Status:** Complete.
- **Implement / result:** Extend retained identity/percentage rows with remaining
  opportunities and Spanish respawning/eliminated labels. Keep connected inactive rows
  visible and draw only active bodies; do not add HP indicators.
- **Verify:** 3/2/1/0 stocks, independent rows, own eliminated player still receiving
  snapshots, removed rows on disconnect, and read-only rendering.
- **Explain:** Participation in gameplay differs from connection lifetime.
- **Commit:** `feat(hud): display stocks and lifecycle`.

### 54 — Count the respawn delay in simulation time

- **Status:** Complete.
- **Implement / result:** On a surviving loss start a full three-second timer; only later
  ticks consume it. Publish remaining time and show rounded-up seconds. At zero the state
  is ready for the next step, without returning a body yet.
- **Verify:** Ticks 119/120, overlapping independent timers, no decrement on the loss
  transition, zero-stock exclusion, and no dependence on rendering/wall clock.
- **Explain:** Simulation state owns the delay; the panel only formats it.
- **Commit:** `feat(respawn): count the simulation-owned delay`.

### 55 — Respawn fresh state while preserving stocks

- **Status:** Complete.
- **Implement / result:** At expiry rebuild through the factory at stored spawn,
  preserving outer ID/color/remaining stocks. Reset percentage, total/received velocity,
  facing, jumps, drop exception, cooldown/strikes/targets, and lock controls until
  landing. Do not move the new body again on its return tick.
- **Verify:** Repeated losses/returns, no zero-stock return, full state reset, held A/D
  only after landing, old pulses discarded, and other players unchanged.
- **Explain:** A fresh life resets gameplay while retaining session identity.
- **Commit:** `feat(respawn): restore a fresh character life`.

### 56 — Add visible incoming-hit protection

- **Status:** Complete.
- **Implement / result:** Add a simulation timer and a reusable two-second protection
  helper. Grant it on respawn; reserve match-start wiring for checkpoint 58. Exclude
  protected targets from percentage and impulse, consume attackers normally, and draw a
  neutral ring/text.
- **Verify:** Full duration on return, expiry on the 80th later tick before targeting,
  independent timers, simultaneous attacks, no deferred hits, normal outgoing attacks
  after landing, and ring-outs still possible.
- **Explain:** Temporary hit immunity differs from locked movement.
- **Commit:** `feat(protection): protect newly spawned characters`.

## Milestone 7 — Match entry, results, and repeated play

Checkpoints **57–66**. Separate connections from contenders and compose match phases with the existing pure game simulation.

### 57 — Model match phases and contender roster

- **Status:** Complete.
- **Implement / result:** Introduce serializable match ID,
  waiting/countdown/running/finished phases and a fixed contender map distinct from
  membership. Add waiting lifecycle with zero stocks to admission. Waiting holds gameplay;
  introduce countdown transition next, without declaring a lone connection winner.
- **Verify:** Exact snapshot/match fields, waiting ownership presence, no
  body/physics/stock loss in waiting, and separation from private tokens and room tick.
- **Explain:** Connected members and entrants of one match are different sets.
- **Commit:** `feat(match): model phases and contender ownership`.

### 58 — Start a match after a shared countdown

- **Status:** Complete.
- **Implement / result:** At two or more connections start a full three-second simulation
  countdown; cancel below two. At expiry capture current entrants, increment match ID, and
  rebuild them with three stocks, 0%, locked spawns, and two-second protection. Discard
  pending actions; start performs no additional physics.
- **Verify:** One waiting, 119/120 countdown boundary, joins/leaves around expiry, four
  entrants, repeated ticks, fresh full restart after cancellation, and monotonic room
  tick.
- **Explain:** Starting a match is one authoritative state transition.
- **Commit:** `feat(match): start entrants after a shared countdown`.

### 59 — Keep late arrivals as observers

- **Status:** Complete.
- **Implement / result:** Admit free-slot joins during running/finished with fresh
  identity, waiting lifecycle, zero stocks, and no body. Keep them in snapshots for
  ownership but outside the contender roster, physics, hits, and ranking.
- **Verify:** Observer input, start-boundary joins, capacity still four, snapshot
  presence, and distinction from an eliminated contender. Observers never win or delay the
  current match.
- **Explain:** Admission cannot change an already frozen contender roster.
- **Commit:** `feat(match): keep late arrivals as observers`.

### 60 — Record contender departures as forfeits

- **Status:** Complete.
- **Implement / result:** Before ordinary leave cleanup, capture a contender last
  color/stocks/percentage and mark forfeited. Apply the same path to timeout; observer
  leave does not change the roster. A replacement remains a new observer.
- **Verify:** Active/respawning/eliminated departures, duplicate leaves, reused color/old
  tokens, interleaved tick/join/leave, and no extra stock loss or elimination credit.
- **Explain:** Trusted transport cleanup also updates match eligibility atomically.
- **Commit:** `feat(match): record contender forfeits`.

### 61 — Advance the three-minute match clock

- **Status:** Complete.
- **Implement / result:** Set 180 seconds at match start and decrease only on later
  running ticks, clamped at zero. Waiting/countdown/finished hold the clock; time-limit
  resolution is added at checkpoint 63.
- **Verify:** Start retains 180, 7199/7200 running-tick boundary, processed-time overload
  semantics, and no clock decrement from drawing.
- **Explain:** Authoritative gameplay duration is separate from session lifetime.
- **Commit:** `feat(match): advance the running match clock`.

### 62 — Finish when the last contender remains

- **Status:** Complete.
- **Implement / result:** After the full running tick, count nonforfeited contenders with
  stocks, including respawning. One wins and none draws. Also check trusted departures.
  Capture result once and freeze physics, attacks, respawn/protection, and running time
  while room ticks/snapshots continue.
- **Verify:** Survivor respawning, simultaneous final losses, last-opponent departure,
  zero contenders, observers, duplicate checks, and result preservation after later
  input/leaves.
- **Explain:** Eligibility determines victory, not the number of visible circles.
- **Commit:** `feat(match): commit last-contender results`.

### 63 — Resolve time limits and exact ties

- **Status:** Complete.
- **Implement / result:** At zero, after movement/losses/hits/score capture, rank
  nonforfeited contenders by stocks descending then percentage ascending. Exact best-score
  ties draw; last-contender resolution takes priority. Capture all standings with forfeits
  last and stable ID display order.
- **Verify:** Stocks outrank percentage, equal stocks, exact tie, expiry-tick losses/hits,
  respawning contenders, simultaneous elimination, collection order, and a result immune
  to later reranking.
- **Explain:** Pure ranking and committing a terminal result are separate operations.
- **Commit:** `feat(match): resolve time limits and tied scores`.

### 64 — Show phase, clock, and committed results

- **Status:** Complete.
- **Implement / result:** Extend the neutral panel with shared countdown, mm:ss running
  time, waiting/observer labels, winner/draw/reason, and captured ordered standings.
  Render the committed result even if its winner has disconnected; do not calculate a
  local winner.
- **Verify:** Same-tick panels match, zero time, inactive/observer rows, departed winner
  metadata, standings order, read-only rendering, and no extra client simulation.
- **Explain:** Presentation follows committed facts rather than inferred local outcomes.
- **Commit:** `feat(hud): present match phases and results`.

### 65 — Return to waiting and start another match

- **Status:** Complete.
- **Implement / result:** Add a full five-second finished intermission, consuming only
  later ticks. At expiry rebuild current connections, including observers/eliminated, as
  fresh waiting records and clear old result/actions/state. With at least two, enter a
  full countdown without consuming it on the reset tick. Keep IDs/colors/sockets and room
  tick.
- **Verify:** 199/200 boundary, repeated complete cycles, joins/leaves around reset/start,
  fewer than two waiting, fresh match ID per start, and no stale impulses, action replay,
  or extra clocks.
- **Explain:** A match resets gameplay; a session reset changes identity.
- **Commit:** `feat(match): reset connected players for rematches`.

### 66 — Verify complete shared matches

- **Status:** Pending.
- **Implement / result:** Integrate lifecycle/match/protocol/room/socket regressions and
  extend browser checks and the language/architecture guides. Describe match
  IDs/phases/results as public snapshot data without privileged client commands.
- **Verify:** Run just check. Exercise losses, full respawn/protection, elimination, both
  victory paths, draws, observers, reload/forfeit, and repeated matches. Cover exact
  boundaries and concurrent agent operations with controlled time; report actual
  native/remote checks separately.
- **Explain:** Trace a fall through stock loss, fresh life, result capture, and rematch.
- **Commit:** `test(match): verify complete multiplayer matches`.

## Milestone 8 — Diagnostics, rendering, and interface polish

Checkpoints **67–70**. Improve the implemented game without adding mechanics or changing server authority.

### 67 — Clarify connection diagnostics and game mode

- **Status:** Pending.
- **Implement / result:** Make the existing status show local/shared mode, attempted
  gameplay endpoint, known address/socket/send/EDN/validation/server error categories, and
  available close code/reason. Preserve terminal rejection/timeout and last diagnostics on
  close; clear transient errors only on a successful relevant reply/snapshot.
- **Verify:** Invalid address, closed server, malformed frame, full/expired sessions,
  recoverable admitted OPEN error, replacement session, and endpoint persistence. Browser
  errors do not invent network causes; diagnostics do not own simulation mode.
- **Explain:** Useful diagnostics expose evidence without changing transport authority.
- **Commit:** `feat(connection): clarify mode and connection diagnostics`.

### 68 — Update player panels only when values change

- **Status:** Pending.
- **Implement / result:** Retain rows by exact player ID and current DOM container. Update
  changed text/classes/colors only; account for CSS color normalization. Reuse surviving
  rows on membership changes, skip unchanged clock/result writes, and rebuild standings
  only when their result/container changes. Clear caches on stop.
- **Verify:** Count created nodes and DOM writes across identical/movement-only frames,
  membership changes, timers rounding to the same second, color normalization, and
  container replacement. Canvas still draws every RAF and caches never mutate game data.
- **Explain:** Presentation caches reduce effects while preserving immutable simulation.
- **Commit:** `perf(hud): retain rows and update changed values`.

### 69 — Polish the monochrome responsive interface

- **Status:** Pending.
- **Implement / result:** Identify the controlled visible body with a neutral `Tú` label
  and highlight its row using neutral border/text plus accessible current marker. Use
  assigned identity only after the first shared snapshot; local mode uses player-1. Keep
  A/D/W/S/primary-click help visible. Scale Canvas with CSS, wrap cards/addresses, and
  keep only bodies/identity swatches colored.
- **Verify:** Offline and reassigned identities, pre-snapshot admission, transient
  errors/disconnect/reconnect, inactive/observer rows without floating labels, retained
  row writes, and Canvas state restoration. Check narrow/desktop layouts, contrast,
  IDs/ARIA references, and unchanged 800 × 480 backing dimensions.
- **Explain:** Ownership and layout are presentation decisions, not new input mechanics.
- **Commit:** `feat(ui): polish monochrome responsive presentation`.

### 70 — Consolidate documentation and finish the route

- **Status:** Pending.
- **Implement / result:** Keep README to real quick-start commands, controls, current
  scope, and a guide index. Centralize command details, rules, simulation, architecture,
  Spanish language manual, and local/shared verification in their respective guides.
  Remove duplicate instructions and machine-specific example IPs. Record only
  implementation-backed current behavior.
- **Verify:** Check local links/anchors, shell-block syntax, recipes against justfile, and
  runnable examples. Run final just check when code exists; review the monochrome visual
  contract and complete native-browser/cross-device checklist where available. Distinguish
  tested evidence from pending checks.
- **Explain:** A short entry point and topic-owned guides make the project inspectable.
- **Commit:** `docs: consolidate execution and verification guides`.

## Completion boundary

The route is complete when all 70 checkpoint commits exist, relevant automated
checks pass, and outstanding browser/device verification is reported explicitly.
The final implementation must match the game specification and monochrome
visual contract, without adding mechanics outside the scope boundary. The
roadmap plans the work; it is not evidence that any of it has been implemented.
