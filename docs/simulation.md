# Simulation

The shared simulation lives in `src/clojure_arena/game.cljc`. It receives ordinary values and returns a new world value; it does not read the DOM, Canvas, keyboard state, or wall clock. The browser client owns those effects and calls `game/step` with fixed simulation time.

## Current world

`initial-world` contains one solid rectangular platform and one circular player:

- Platform: top-left `(170, 280)`, size `460 × 16`, neutral gray `#9c9c9c`.
- Player: center `(400, 160)`, radius `16`, initial velocity `(0, 0)`, cyan `#22d3ee`.
- Gravity is `980` world units per second squared, in the positive-Y direction.

The arena background is `#101010`. The player body is the only colored gameplay object; the platform remains neutral.

## One simulation step

`step` accepts a world, an input map, and `dt` in seconds. Missing `:horizontal` input defaults to zero. For the current player it:

1. Updates horizontal velocity with `accelerate-horizontal`. A/D intention targets `-220` or `+220` units per second, approaching the target by at most `880 × dt`. Neutral intention calls `apply-horizontal-friction`, which uses `horizontal-friction` (`1100` units per second squared) to approach zero without crossing it. Both rules apply in the air and on the platform.
2. Adds gravity to vertical velocity by `980 × dt`.
3. Calls `collide-platform` to integrate movement and resolve contact with the single platform.

At the client's 25 ms tick, acceleration changes horizontal speed by at most `22` units per second; neutral friction changes it by at most `27.5`. Starting from rest, ten sustained movement ticks reach the 220-unit/s target. Starting at 220, neutral braking stops in eight ticks and covers 19.25 additional units in free space.

## Movement and contact

`move-player` advances the circle center using velocity and elapsed seconds. When the player is standing on the platform's top, `supported-travel-time` limits horizontal travel to the platform edge. If the player leaves that edge, the remaining time is simulated as a fall.

`platform-contact` checks an existing overlap and swept contact with each rectangular face and rounded corner. `overlap-contact`, `face-contact`, and `corner-contact` produce contact geometry. `resolve-contact` removes only velocity directed into the contact normal, preserving separating and tangential motion. `collide-platform` can process up to four contacts in one tick and uses a `1e-9` contact tolerance.

The scene has one platform. W captures a fresh jump request, consumed by one tick; an unlocked supported player launches at −420 units/s before gravity. Airborne requests without an allowance are discarded. Initial spawn controls stay locked until a flat-top landing; gravity and contact continue during the lock. `grounded?` checks the center within the top span, feet at the top, and zero vertical speed. `supporting-platform` returns that platform or nil. The code does not impose an arena-edge rule.

## Inputs and rendering

The client turns `KeyD` and `KeyA` into `:horizontal` values `1`, `-1`, or `0`. With both keys held the result is zero, so neutral friction applies. A browser blur clears held keys; it does not directly edit the world. `client/advance-world!` calls `game/step` once for each accumulated 25 ms tick, and `client/draw-world` renders the resulting world.

See [Architecture](architecture.md) for ownership and [Simulation verification](simulation-verification.md) for checks.

The player tracks `:air-jumps-left`, initially one. Only supported top landing
restores it. Ground launch and edge departure preserve it; side, underside,
and rounded-corner contact do not refill it. A fresh airborne request with allowance remaining replaces vertical velocity with −420, then spends the allowance. A third request is rejected.
