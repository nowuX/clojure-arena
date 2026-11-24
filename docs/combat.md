# Local combat

## Strike acceptance

The canvas queues one `:attack?` intention. `step-player` calls `step-melee`
before movement and before a landing can unlock spawn. Each player begins with
`:melee-strike-id 0` and `:melee-cooldown-left 0`. Decrement the cooldown by dt,
clamp at zero within `cooldown-epsilon` (1e-9), and accept a fresh request only
when unlocked and ready. Acceptance increments that player's sequence and sets
0.25 seconds. At 25 ms per tick, the tenth later tick can accept another strike.
Rejected clicks are consumed, never stored until readiness. Misses still spend
the cooldown. Acceptance triggers the geometric query and percentage update below. Grouped impulses apply after targeting.

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

`apply-melee-hits` groups selected IDs with their attacking facings and adds
10 percentage points per hit.
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

## Trial impulse calculation

`melee-knockback-impulse` is a pure calculator returning `{:dvx ... :dvy ...}`
in units/s. Its default named config is `{:base-impulse 200 :percentage-scale 3}`;
a third argument can replace both values. Magnitude is `K = 200 + 3P`, where P
includes every selected hit on that target in the tick. Right contributes
`(0.8K, -0.6K)`, left contributes `(-0.8K, -0.6K)`. Count directions before
scaling to make their combined axes independent of hit order. Do not average
or clamp to voluntary speed. Zero hits returns zero; percentages above 100
continue scaling.

One right hit at final P = 10 gives (184, -138); two opposing hits at final
P = 20 give (0, -312). The calculator itself does not move a body; `apply-melee-hits` applies its
returned velocity change after targeting.

## Received motion and contacts

For each target, first compute final P from all pending hits, then calculate
all contributions at that same P. Add their summed impulse to both total
`:vx`/`:vy` and retained `:knockback-vx`/`:knockback-vy`. Resolve sequence markers
with those effects, so replay changes neither percentage nor motion. Hits do
not advance position a second time; new motion starts on the following tick.

Each movement tick subtracts old received X from total X to recover voluntary
X. Apply A/D acceleration or neutral friction to the voluntary share and the
same 1100 units/s² friction independently to received X, then recombine.
Only voluntary motion targets 220; total speed can exceed it. Gravity updates
total Y while retained Y records the received share. Valid ground/air jumps
replace total Y with -420 and clear retained Y before gravity, preserving
received X with its ordinary decay. A rejected jump clears no motion.

`resolve-contact` projects total velocity against an inward contact normal and
projects the retained vector with the same normal. This prevents canceled
received motion from resurfacing on the next tick and preserves the sum of
received and voluntary shares at rounded corners. Rising through semisolids
still works; a supported top landing clears inward vertical received motion,
unlocks spawn, and restores the air jump. None of this changes remembered facing.

## Local integration boundary

The four colored records are local fixtures. Only cyan `:player-1` receives
browser A/D/W/S/click input. The same scene supports movement, ground and air
jumps, drop-through, facing, cooldowns, targeting, accumulation, and knockback.
Connected play uses the same pure mechanics with server-owned inputs and
snapshots; the fixture controls above describe disconnected fallback only.
Stocks, respawn and incoming-hit protection use the lifecycle described in
[Simulation](simulation.md). [Matches](matches.md) gates connected combat to
running contenders. HP and automatic opponents are outside this implementation.
See the [architecture](architecture.md) for the complete tick and the
[verification guide](simulation-verification.md) for reproducible checks.


## Lifecycle and incoming protection

Only active bodies can attack or be selected. Resolve ring-outs after movement
and before targeting. Protected active targets are excluded both by the query
and by the resolver; a consumed strike cannot become a delayed hit after the
protection expires. Outgoing attacks remain normal after landing unlocks them.
The two-second protection timer decrements before that tick's targeting and
expires on the 80th later 25 ms tick. It blocks percentage and impulse together,
not gravity, motion, platform contacts or ring-outs.

`immune-player?` combines spawn protection with an active dash. The nominal
180 ms dash immunity is quantized to the first 25 ms boundary at or after it:
the activation tick is dash tick 1, hits evaluated on ticks 1–8 are consumed
without effect, and tick 9 hit evaluation sees a normal target. Like protection,
a dash-rejected strike never becomes a delayed hit.

## Antiknockback (Q)

A fresh Q press (`:antiknockback? true`) sets `:antiknockback-seconds-left 5`
and `:antiknockback-cooldown-left 10` for an active, unlocked body whose cooldown
is zero. It is resolved before locomotion, so it can start on the same tick as a
dash or a strike; it never ends spawn protection. Both timers decrement at the
start of every later active tick: Q covers the activation tick plus 199 later
ticks, expires before hit evaluation on the 200th, and can be used again 400
ticks after activation. Requests while Q is active neither stack nor refresh.

A Q press during an already active dash sets `:antiknockback-buffered? true`.
On the first tick after the dash it activates before movement and damage if the
cooldown is ready, and is discarded otherwise, so the tick 9 hits already meet
an active Q.

While Q is active, `apply-melee-hits` still adds the hit percentage and consumes
the strike but adds no impulse to total or received velocity. Earlier received
motion, gravity, contacts and ring-outs continue normally. Immune targets are
excluded before Q is considered, so spawn or dash immunity blocks the percentage
too. A ring-out clears active and buffered Q; the respawned body has a ready
cooldown. Canvas draws a neutral dashed ring and the panel row shows «Q activa
N s», «Q tras el Dash», «Q en N s» or «Q lista».

## Percentage recovery (E)

A fresh E press (`:recover? true`) sets `P = max(0, P * 0.7)` and
`:recover-cooldown-left 12` for an active, unlocked body with a ready cooldown
and a positive percentage; at exactly zero it is rejected without spending the
cooldown. It is a relative reduction (80 → 56, 20 → 14), applied before
locomotion and before that tick's hits, so a simultaneous strike adds its +10 to
the healed value and computes its impulse from that final percentage. Earlier
impulses are never rewritten: E changes no stock, velocity, received motion,
air jump, protection or other cooldown. It can start with Q, a dash or a strike
on the same tick, is ready again 480 ticks after activation, and resets with
the respawned body.

An E press during an already active dash sets `:recover-buffered? true` and is
retried on the first tick after the dash, like Q; a buffered E that finds a zero
percentage or a cooling E is discarded. Ring-out clears the buffer.

The simulation, snapshots and `match/result-for` keep full floating-point
precision, so 56 still beats 56.04 at the time limit and only an exact stored
tie draws. `format-percentage` shows at most one decimal and drops a trailing
`.0` in panel rows and standings («56 %», «17.5 %»). The panel row shows «E
lista», «E tras el Dash» or «E en N s».
