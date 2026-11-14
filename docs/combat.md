# Local combat

## Strike acceptance

The canvas queues one `:attack?` intention. `step-player` calls `step-melee`
before movement and before a landing can unlock spawn. Each player begins with
`:melee-strike-id 0` and `:melee-cooldown-left 0`. Decrement the cooldown by dt,
clamp at zero within `cooldown-epsilon` (1e-9), and accept a fresh request only
when unlocked and ready. Acceptance increments that player's sequence and sets
0.25 seconds. At 25 ms per tick, the tenth later tick can accept another strike.
Rejected clicks are consumed, never stored until readiness. Misses still spend
the cooldown. Acceptance triggers the geometric query and percentage update below. Physical
impulses are not integrated yet.

## Post-movement targeting

`step` first advances every player, then detects newly incremented strike IDs.
Each new strike calls `melee-target-ids` against the same final player map.
Store its set in `:melee-target-ids`; clear that field on the next step even if
no new attack is accepted. No later-tick search extends a miss.

Reach is a center-to-center forward half-disk of radius 60: squared distance
is at most 3600 and dx is strictly positive for right or negative for left.
Exclude the attacker ID and the vertical center line. Radius does not expand
reach, and platforms do not occlude it. Multiple targets and simultaneous
attackers use one snapshot, independent of player-map iteration order.

## Percentage and presentation

`apply-melee-hits` groups selected IDs and adds 10 percentage points per hit.
Each record starts with `:accumulated-percentage 0`; values exceed 100 without
changing normal movement or representing HP. Hits on the same target add up.
The next step clears target sets, so the ordinary loop has no lingering hitbox.
Explicit replay protection also guards the resolver itself.

`frame` and `init` dereference the world once and pass that same value to Canvas
and `render-players!`. The panel beneath the arena lists ID, color swatch, and
percentage, ordered by ID. Only the small swatch has hue; surfaces and text are
neutral. Rendering reads state without updating gameplay.

## Exactly-once resolution

Each attacker starts with `:melee-resolved-strike-id 0`. `apply-melee-hits`
selects only sequences greater than that marker, groups their selected hits,
and returns all effects with the updated markers in one immutable map. Even
an empty target set or a disappeared target consumes the strike. Reapplying
this result cannot add percentage again. Separate attackers may both resolve
sequence 1; a later accepted sequence can hit the same target again.
