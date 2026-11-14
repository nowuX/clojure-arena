# Simulation

The shared simulation is `src/clojure_arena/game.cljc`. Its functions receive values and return a new world; keyboard, clock, DOM, and Canvas effects belong to `client.cljs`.

## World and movement

`initial-world` contains one solid, three visible semisolid entries, and four ID-keyed local fixture players.
Descending players land on semisolid tops and upper endpoint arcs; rising,
side, and underside movement passes through.
The main platform entry and first player are:

```clojure
{:platforms [{:id :main :type :solid :x 170 :y 280
              :width 460 :height 16 :color "#9c9c9c"}]
 :players {:player-1 {:x 400 :y 160 :vx 0 :vy 0
                      :knockback-vx 0 :knockback-vy 0 :radius 16
                      :spawn-locked? true :air-jumps-left 1
                      :drop-through-platform-id nil
                      :color "#22d3ee" :facing :right
                      :melee-cooldown-left 0 :melee-strike-id 0
                      :melee-resolved-strike-id 0 :melee-target-ids #{}
                      :accumulated-percentage 0}}}
```

The background is `#101010`; the platform is neutral gray and only player bodies and identity swatches are colored. Gravity is `980` units/s² downward. Horizontal intention targets ±220 units/s at up to 880 units/s². Neutral input applies `horizontal-friction` at 1100 units/s² toward zero, without reversing velocity. At the client's 25 ms tick those changes are at most 22 and 27.5 units/s respectively. A locked spawn forces horizontal intention to zero while gravity and collision continue. From rest, ten held-direction ticks reach 220; from 220, neutral braking stops in eight ticks and travels another 19.25 units in free space.

`step` receives a world, an inputs-by-player map, and `dt` in seconds. For
example, `{:player-1 {:horizontal 1 :jump? true}}` controls only that ID. It
reduces over existing players, calling `step-player` once per record with the
same platforms and dt. Missing or nil input is neutral; unknown IDs never
create players.

`step-player` receives one player, the platforms,
`{:horizontal ... :jump? ... :drop? ...}`, and dt. A missing direction defaults
to zero; missing action flags default to false. Its order is:

1. Clear a finished or stale drop exception and neutralize horizontal intention while spawn locked. Negative/positive accepted intention remembers left/right facing; neutral preserves it.
2. Find current support. An unlocked S request on a semisolid records its ID as `:drop-through-platform-id`; this wins over simultaneous W. Otherwise call `apply-jump` before gravity. Ground jumps preserve the air allowance; airborne jumps spend one. Locked or exhausted requests are discarded.
3. Separate total X into voluntary and received shares. Accelerate/brake the
   voluntary share, damp received X independently, and recombine. Add `980 × dt`
   to total vertical velocity. Valid jumps clear received Y.
4. Call `collide-platforms` and clear the exception again using the resulting position.

From a supported center Y of 264, the first launch tick ends at Y = 254.1125 with vy = −395.5. Holding W does not change jump height. Each fresh W press is one request. A grounded jump and the single allowed air jump both replace vertical velocity with -420 before gravity is added.

## Support, spawn, and allowance

`grounded?` is true when vertical velocity is zero, the circle center lies within the platform's horizontal span, and its feet meet the top within `contact-epsilon`. `supporting-platform` receives the platform vector and returns the supporting entry, otherwise `nil`. It excludes the active drop exception and breaks coincident support ties by stable ID.

The initial player falls with controls locked. A resolved supported top landing sets `:spawn-locked?` to false and restores `:air-jumps-left` to one. Side, underside, and corner contacts do neither. A ground jump preserves the allowance; walking off an edge also preserves it. An accepted air jump reduces it to zero, and a later supported landing restores it. `apply-jump` checks support at the start of the tick, so an action rejected during the landing tick is not buffered for after landing.

## Contact and integration

`move-player` advances the circle center using velocity and seconds. `supported-travel-time` bounds motion along a supported top and lets gravity begin after leaving an edge. `earliest-contact` chooses the earliest contact across eligible platform surfaces, using stable ID order for exact time ties. `platform-contact` checks overlap and swept contact against each rectangle's faces and rounded corners; `resolve-contact` removes inward total normal velocity and projects the retained
received vector with the same contact normal. `collide-platforms` allows up to four contacts per tick and uses a `1e-9` tolerance.

The world contains the main solid plus three neutral outlined semisolids; constructed solid collections share the same four-contact budget. Any solid or semisolid flat top supports jumping. See [Architecture](architecture.md) for ownership and [Simulation verification](simulation-verification.md) for checks.

## Platform presentation

The left and right outlines are at (200, 210) and (480, 210), each 120 × 8.
The upper outline is at (340, 130), also 120 × 8. `draw-platform` uses
`fillRect` for solids and `strokeRect` for semisolids; type is conveyed by
shape treatment with neutral colors.

## Semisolid contacts

`semisolid-contact` accepts only descending top or upper-corner sweeps.
`semisolid-corner-overlap` corrects upper endpoint penetration while moving
around an edge. There are no semisolid side walls or underside contacts.
Support uses the circle center inside the flat span; corner-only contact
retains the spawn lock and spent air jump. Supported travel ends at the
center endpoint, then remaining falling time follows the rounded boundary.

## Drop-through and input lifetime

S captures a separate `:drop?` request with the same fresh-press lifetime as W.
`read-inputs` preserves both flags; `consume-inputs!` clears both action pulses
and retains held A/D. Blur, stop, and restart clear all input. Holding S does
not drop again after landing on another platform; a fresh press is required.

A drop is eligible only while unlocked and supported by a semisolid flat top.
Solid, airborne, locked, and corner-only requests do not create an exception.
An accepted drop applies ordinary gravity with no teleport or extra impulse;
the first neutral 25 ms tick lowers the center by 0.6125 and adds vy = 24.5.
It preserves the air jump. Other platforms retain their normal collision rules.

`drop-through-platform?` checks the player's stored ID against each semisolid.
`clear-drop-through` clears that ID when the whole circle is below the bottom
(`y - radius >= platform-y + height`), feet return above the top with the
1e-9 tolerance, or the platform is missing or no longer semisolid. Cleanup runs
before and after movement. An air jump during partial traversal can rise back
above the top, clear the exception, and land on the same platform again.

## Player records

`make-player(color, x, y)` creates a plain immutable record with zero velocity,
radius 16, locked spawn controls, one air jump, no drop exception, and right facing.
`local-player-fixtures` supplies cyan at (400, 160), crimson at (260, 160),
amber at (540, 160), and green at (350, 160). IDs are map keys, independent
of color. `draw-world` draws each value from `:players` without changing it.
`step` advances all four records independently. The browser wraps each consumed
input in `{local-player-id inputs}`, where `local-player-id` is `:player-1`.
The other three records receive neutral input and still fall, land, and brake.
Locomotion reads no other player record, so map iteration order cannot change the
result. Locks, air jumps, and drop exceptions remain local to each record.

## Remembered facing

Each record owns `:facing`, initially `:right`. In `step-player`, spawn locking
neutralizes horizontal input before a negative or positive intention records
`:left` or `:right`. This happens before acceleration: requesting left while
vx is still positive immediately faces left. Neutral input, both held keys,
blur, friction, gravity, jumps, drops, contact, and velocity-only changes retain
the previous facing. A direction rejected during the first landing tick takes
effect only if still held on the following tick.

`draw-player` draws a neutral `#f2f2f2` dot of radius 3 at half the body radius
left or right of its center. It reads facing rather than inferring it from vx.
Only player bodies and their identity swatches use hue.

## Primary-pointer intention

The arena canvas alone listens for `pointerdown` with button 0. A press stores
`:attack?` in `pending-attack`; reads preserve it and the next fixed tick consumes
it alongside W/S. Multiple presses before a tick coalesce. A held pointer does
not repeat. Blur, stop, and restart discard pending attacks; replacing the canvas
removes its old listener. The next tick may accept it through `step-melee`; target selection, percentage,
and received impulses remain simulation decisions.

After every player finishes movement, `step` selects targets for new accepted
strikes from that shared result and calls `apply-melee-hits` once. Percentage
and summed impulses use the final target percentage. See [Local combat](combat.md)
for cooldowns, exactly-once resolution, and retained motion.
