# Simulation verification

A Node pass or release compile checks only its own boundary. The browser checklist below is manual guidance, not a report that native-browser checks have run. See the [development guide](development.md) for prerequisites and command details.

## Automated checks

Use [Development](development.md#verify) for Node, JVM and release commands.
Node fixtures check acceleration/friction, spawn lock, support, jump/drop
consumption, frame cadence, cleanup, swept contacts, melee targeting and
knockback. Client fixtures use EventTarget, controlled RAF and DOM/Canvas
doubles; they do not establish native focus or Canvas pixels. A release compile
establishes compiler compatibility, not browser startup or appearance.

## Native browser checklist

Use [offline practice](development.md#offline-practice) on the service host.

- Confirm `Arena lista.` appears with the dark background, filled main platform, three neutral outlined semisolids, and four distinct player bodies (cyan, crimson, amber, green), each with a white facing dot.
- On first fall, hold D and press W. The player should stay at its spawn X and should not jump. After landing on the platform top, controls unlock; the earlier W request must not replay. A held D direction should begin moving on the following tick.
- Hold D, release it, then reverse with A. Check acceleration and gradual braking. Hold both keys together and check neutral braking.
- Walk off either platform edge and confirm falling resumes. A fresh W press after leaving an edge can use the single air jump. A complete blast-bound exit spends one opportunity; survivors return after
  three simulation seconds.
- Release W, then make a fresh W press while grounded. Confirm a ground jump. While airborne, make another fresh W press and confirm one air jump. A third fresh press must not launch; it must not be saved for later.
- Land on the platform and confirm a fresh W press can launch again. This checks that supported landing restores the air-jump allowance.
- While holding a direction or with a W/S/click request pending, move focus away from the page. Confirm blur clears held directions and all pending actions. Repeat startup/stop through the development workflow and check for duplicate input response or animation loops.

A browser can verify event ordering, focus behavior, native `requestAnimationFrame`, and visible Canvas output. Node doubles cannot establish those behaviors. Use deterministic automated fixtures for side, underside, and corner-contact constructions that are not practical to reproduce through this local play loop.

## Multiple-platform checks

- Move toward a side outline and jump through it from below. On the descent,
  land on its top and make another ground jump. Use the extra air jump to
  reach the upper outline.
- Leave each semisolid slowly and at full speed from either edge. The circle
  should start descending around the upper endpoint rather than penetrating
  the rectangular platform or catching a side wall.
- Automated fixtures isolate the main platform for earlier movement tests;
  semisolid and scene tests exercise the complete four-entry scene separately.

## Drop-through checks

- On each of the three semisolids, tap S: the body starts falling under normal
  gravity. S on the main solid keeps support; a simultaneous W still jumps
  from that solid. On a semisolid, simultaneous W/S drops instead of jumping.
- Keep S held after dropping onto another platform. It should retain support
  until a fresh S press. Only the originally supporting semisolid is ignored.
- Drop partly through a small platform, release S, then press W while the
  air jump remains. Rise above it and descend onto it again.
- Tap S and lose focus before the next tick: the pending drop is discarded.
  Automated fixtures additionally test full-body clearance, missing IDs,
  coincident contacts, and lower solid/semisolid interception.

## Player record checks

All four bodies fall and unlock after landing. A/D, W, S, and primary click control only cyan
`:player-1`; the other fixtures receive neutral input. Their jumps, locks, and
drop exceptions remain independent. These fixtures are local records, not
network connections.

Automated recording tests draw one through four records without modifying the
world. Pure tests exercise simultaneous independent inputs, missing and unknown
IDs, nil inputs, and reversed map order. Client event tests verify that controls
reach only `:player-1` and never create it if it is absent.

## Facing checks

- After unlocking, hold D and then reverse to A. The dot moves left as soon as
  A is accepted, before rightward momentum fully brakes.
- Release both keys, hold A/D together, or move focus away: the last facing
  remains during braking and falling. Jumping, dropping, and landing retain it.
- Reload and hold A during the initial fall. The dot stays right through the
  first landing tick; a still-held A turns it left on the following tick.
- The three fixtures retain their own initial right facing. Automated pure
  tests additionally drive multiple players with opposite intentions and
  change velocity independently. A recording Canvas checks dot placement and
  neutral color; native pixels remain a manual check.

## Local combat checks

- Allow all four bodies to land. Cyan is the only controlled player. Tap A to
  face green, then primary-click inside the arena while their centers are within
  60 px. Green gains 10 percentage points and launches left/up. The panel row
  matches its ID/color and keeps the percentage after landing.
- Holding the pointer does not repeat. Click again only after 0.25 simulation
  seconds. An early click is discarded; a fresh click is needed after readiness.
  Reposition and repeat to see increasing launch strength and values above 100.
- A click outside the canvas or with a secondary button has no effect. A click
  during initial locked fall cannot be saved until landing. Click then blur
  before the next tick: the pending request is discarded.
- Jump and click together while unlocked. On a supported semisolid, S/W/click
  together drops instead of jumping while independently requesting melee.
- Walk or jump toward the upper semisolids, face a nearby body and hit.
  Watch its trajectory through one-way surfaces and later landing. Surviving fixtures return after three simulation seconds with reset
  percentage and two-second hit protection. At zero opportunities, reload
  restores the local sandbox.

Automated fixtures exercise clicks through `EventTarget`, actual `game/step`,
and controlled RAF at different frame cadences. Recording DOM/Canvas doubles
check the same-frame world and displayed values. Pure tests cover opposing
simultaneous hits, wall/corner projection after real hits, rejected/valid jumps,
and semisolid rise/landing/drop. These passes do not establish native pointer,
focus, Canvas pixels, or cross-device behavior; those remain manual checks.


## Stocks, respawn and protection checks

- Walk off the arena far enough for the whole circle to cross a blast bound.
  The row loses exactly one opportunity, retains percentage and shows the
  three-second respawn countdown; its body is absent during that delay.
- On return, verify 0%, original spawn, right facing, one air jump and locked
  controls until top landing. Held A/D begins only after landing; earlier W/S
  or click pulses must not replay. The return tick does not also move the body.
- During the two-second neutral ring, incoming strikes change neither percentage
  nor impulse. After it disappears, a fresh valid strike works. A protected
  player may attack after landing, and can still lose a stock by exiting.
- Repeat until zero opportunities. The row stays «Eliminado» with no body or
  timer and cannot hit or be hit. These offline sandbox checks have no match
  result; use [Multiplayer verification](multiplayer-verification.md) for victory
  and rematch checks on connected pages.

The automated boundary tests check 119/120 respawn ticks, 79/80 protection
ticks, complete/partial exits on every edge, simultaneous exits/attacks,
independent timers and exact snapshot schemas on JVM and ClojureScript.

## Presentation checklist

- In offline practice, verify the cyan body has a neutral `Tú` label and its
  identity row has a neutral selection border. An inactive body has no floating
  label, while its row remains identifiable.
- At desktop and 320/375 px viewport widths, check proportional Canvas scaling,
  readable controls, wrapped cards/addresses and no horizontal page overflow.
  The backing surface remains 800 × 480; CSS size changes no game coordinates.
- Verify IDs, swatches, percentages, opportunities and lifecycle labels remain
  readable. All nonidentity colors, including protection, selection, results,
  platforms and focus indicators, must stay grayscale.

DOM-count regressions check retained rows, unchanged writes, CSS color
normalization, membership/container replacement and rounded clocks. These
measure presentation effects in Node; native layout and screen-reader behavior
remain manual checks.
