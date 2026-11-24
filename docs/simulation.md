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

## Blast bounds

The world stores `:blast-bounds {:left -80 :right 880 :top -160 :bottom 560}`.
`outside-arena?` is a pure whole-circle query: exact touch or partial crossing
is not an exit. The circle must be strictly beyond one limit. The visible
800 × 480 canvas is not the loss boundary. Bounds are validated world data in
snapshots, independent of platform geometry; the query itself never changes players or consumes opportunities.

## Player opportunities

`make-player` initializes `:stocks 3` and `:lifecycle :active`. Stocks are
remaining opportunities, independent of percentage and motion. Snapshots
allow active/respawning bodies with 1–3 integer stocks and eliminated records
with 0. Match admission adds waiting records with zero stocks and timers.
Only running roster players enter the connected gameplay simulation; the
stock-loss transition below controls participation within it.

## One stock per exit

The tick moves active bodies, then `resolve-ring-out` checks each result before
melee targeting. An exit decrements once, freezes motion, clears drop/cooldown
and pending hit effects, and enters respawning or eliminated. Percentage stays
visible until a fresh life replaces it. Inactive records neither move nor hit
nor receive hits. Drawing omits their bodies; connection identity and input
ownership remain. Room ticks consume inactive action pulses normally, retaining
only the latest direction. Surviving bodies return through the stored-spawn factory at delay expiry.

## Opportunity panel

Canvas draws only active bodies. The panel retains every connected identity
with color swatch, percentage, remaining opportunities and Spanish lifecycle
label. Inactive players still receive snapshots under their assigned ID;
stock loss does not disconnect them. Rendering is read-only and removes a row
only when its player leaves the world.

## Respawn delay

A surviving ring-out installs a full `:respawn-seconds-left 3`; that loss tick
does not reduce it. Only later inactive ticks subtract dt. The shared epsilon
clamps tiny residuals to zero, reaching readiness on the 120th later 25 ms tick.
Eliminated players have no timer. Snapshots validate finite values from 0 to 3;
the panel rounds up simulation-owned seconds and never measures wall time.
At expiry, rebuild a fresh body without moving it again on the return tick.

## Fresh-body return

The factory stores `:spawn-x/:spawn-y`. On delay expiry, `step-respawn` rebuilds
through that factory, preserving only the outer player ID, color and remaining
stocks. Percentage, total/received motion, facing, jumps, drop exception,
cooldown, sequences and targets reset. Controls stay locked until supported
landing. The return branch ignores actions and performs no physics; later
ticks resume ordinary falling. Inactive action pulses are consumed by room
and local input clocks, so no old jump/attack can replay after landing.


## Incoming-hit protection

New ordinary admissions start with no protection. `grant-spawn-protection`
returns a new active player with `:protection-seconds-left 2`; inactive values
are unchanged. Respawn grants the full duration on the return tick. Only later
active ticks consume it, before motion/ring-out and melee targeting. At zero,
that tick's hits can apply. A protection-rejected strike is still consumed;
protection does not defer impacts or prevent ring-outs. Match start also grants
two full seconds to each freshly rebuilt entrant without consuming them on its
start tick.

Canvas adds a neutral white ring around a protected body and the panel adds
«Protegido N s» with rounded-up simulation time. Both disappear at zero. Body
colors remain reserved for identity; stocks, lifecycle and protection are
separate fields. At zero stocks a player remains connected and eliminated;
the match layer captures victory or time-limit results and later rebuilds current
connections for a rematch. The offline sandbox keeps its simpler match-free world.

## Dash

A fresh Shift press (`:dash? true`) starts a dash for an active, unlocked body
whose `:dash-cooldown-left` is zero. Each tick resolves at most one locomotion
action in the order supported S drop, eligible W jump, then dash; an ineligible
drop (midair or on a solid top) or jump (no air jump left) does not block it.
Activation locks facing, sets `:dash-ticks-left 8` and `:dash-cooldown-left 2.5`.

`step-player` decrements both values at the start of every later active tick,
before reading requests, so the activation tick never consumes cooldown and the
2.5 s wait spans exactly 100 ticks. The integer tick counter keeps the 8-tick
movement and immunity boundary exact. While it is positive, voluntary X is
replaced by `400 * facing` (80 px over 8 ticks at neutral received X), and
steering, jumps, drops, attacks and further dash requests are ignored, including
an attack sent on the activation tick. Received X still decays separately;
vertical velocity, gravity and the air-jump allowance are untouched, so air
dashes are allowed.

`collide-platforms` receives the dash direction. A solid contact whose normal
opposes it sets `:dash-stopped? true` and consumes the rest of that tick's
travel; later dash ticks use zero voluntary X until the original deadline, so
an early stop never shortens the input lock or immunity. Flat tops, forward
corner corrections and one-way semisolid rules do not stop travel. On the tick
after the dash, only voluntary X is clamped to 220 before ordinary acceleration
or friction. A ring-out clears the active dash. Until checkpoint 73 the
cooldown belongs to the physical body, so a respawn starts with a ready dash.
The local sandbox steps the same rules on every local tick.

Canvas draws two short neutral streaks behind a dashing body, and each panel row
shows «Dash listo», «Dash activo» or «Dash en N s» with rounded-up seconds.
