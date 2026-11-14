# Local combat

## Strike acceptance

The canvas queues one `:attack?` intention. `step-player` calls `step-melee`
before movement and before a landing can unlock spawn. Each player begins with
`:melee-strike-id 0` and `:melee-cooldown-left 0`. Decrement the cooldown by dt,
clamp at zero within `cooldown-epsilon` (1e-9), and accept a fresh request only
when unlocked and ready. Acceptance increments that player's sequence and sets
0.25 seconds. At 25 ms per tick, the tenth later tick can accept another strike.
Rejected clicks are consumed, never stored until readiness. Misses still spend
the cooldown. Acceptance triggers the geometric query below; percentage and physical effects
are not applied yet.

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
