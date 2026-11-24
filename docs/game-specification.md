# Game specification

This document defines the core target for completed checkpoints 01–70 and the
planned abilities and recovery extension for pending checkpoints 71–87 in the
[implementation roadmap](implementation-roadmap.md). Implementation progress is
tracked in the roadmap. Pending checkpoints are not yet implemented and must not
be built before their checkpoint arrives.
Values labeled trial are concrete defaults for testing, not proven optimal
balance.

## Platform and state

- Browser client in ClojureScript; Clojure/JVM server using `http-kit` and Ring.
- Shared pure simulation in `.cljc`, using immutable maps and named constants.
- HTML5 Canvas with 800 × 480 logical coordinates; X increases rightward and Y
  downward. CSS follows the [monochrome design system](design-system.md).
- Simulation step: 0.025 seconds, nominally 40 Hz. Browser RAF draws; accepted
  network clients do not advance physics.
- Network messages are validated EDN over WebSocket. Clients send intentions,
  never positions, ownership tokens, authoritative ticks, or player identity.
- One room agent serializes join/input/tick/leave. Scheduler pulses allow at
  most one pending tick and drop additional pulses while busy, with no replay.
  Gameplay timers therefore measure processed simulation time.
- Snapshot publication is independent of physics, with one delivery in progress
  and at most the newest pending snapshot. No unbounded delivery queue.

## Scene

| Platform | Type | Top-left | Size |
| --- | --- | --- | --- |
| `:main` | Solid | `(170, 280)` | `460 × 16` |
| `:left` | Semisolid | `(200, 210)` | `120 × 8` |
| `:right` | Semisolid | `(480, 210)` | `120 × 8` |
| `:upper` | Semisolid | `(340, 130)` | `120 × 8` |

Semisolid coordinates and sizes are trial defaults. Solid platforms block
faces and rounded corners against a circular player. Semisolids allow rising
and side/underside traversal, catch downward top contact, and support rounded
upper endpoint contact without invisible rectangular support. A supporting top
requires the center X inside its inclusive flat span; endpoint contact outside
that span does not restore jumping or unlock spawn controls.

Blast bounds, added with ring-outs, are left −80, right 880, top −160, bottom
560. Exit only when the whole circle is strictly outside: left `x + r < -80`,
right `x - r > 880`, top `y + r < -160`, bottom `y - r > 560`. Touching or partial
crossing preserves stocks. Bounds are gameplay limits, not platform geometry.

## Movement and input

| Parameter | Value |
| --- | --- |
| Player radius | 16 px |
| Voluntary horizontal speed | 220 units/s |
| Trial horizontal acceleration | 880 units/s² |
| Trial horizontal friction | 1100 units/s², same on ground and in air |
| Gravity | 980 units/s² |
| Ground and air launch velocity | −420 units/s |
| Air jumps | One, restored only on supported top landing |

A/D supplies horizontal intention −1/0/1; both keys means neutral. W requests a
jump; S requests drop-through; primary click inside the canvas requests melee.
Fresh actions survive a tap between ticks and are consumed once. Holding a key,
autorepeat, or holding the pointer cannot repeat an action. Blur/stop/restart
clears input. Discard rejected actions rather than buffering them until eligible.

Introduce spawn locking with explicit top support: gravity/collisions advance,
but voluntary movement, jumps, drops, and attacks stay disabled until first
supported landing. Held A/D can act on the following tick; old action pulses
need a fresh press. Later falls keep controls unlocked. Respawn reapplies the lock.

Use launch velocity, then gravity, then position. A ground jump preserves the
air allowance. An air jump replaces vertical velocity and spends the allowance;
side/underside/corner contact does not refill it. An accepted semisolid drop
wins over a simultaneous jump, preserves the air jump, adds no teleport/impulse,
and ignores only that player's supporting platform. Clear the exception after
full passage, after returning feet above the top, or if the platform disappears.

Local RAF stepping accumulates elapsed milliseconds in 25 ms steps, counting
at most 100 ms per frame and carrying the remainder. Restart never duplicates
RAF or listeners. Connected elapsed time/actions cannot replay into local fallback.

## Players and sessions

All four identities share physics and abilities. Body colors are cyan, crimson,
amber, and green; see the design system for exact values. Offline fixtures use
`:player-1` at `(400, 160)`, `:player-2` at `(260, 160)`, `:player-3` at `(540, 160)`,
and `:player-4` at `(350, 160)`. The page controls only local `:player-1`.

Network color spawns are `(340, 160)`, `(380, 160)`, `(420, 160)`, `(460, 160)` in
palette order. Every fresh connection gets a new monotonic `:player-N` ID and an
available color, up to four accepted connections. Reject a fifth explicitly.
Duplicate internal joins are idempotent. Each socket's private token owns only
its member's inputs; tokens remain outside snapshots. Closing or timing out
atomically removes member/body/input and releases color. A reused color never
restores identity. Tabs, cookies, reloads, and reconnects are new sessions.

The browser derives `/ws` from its page origin or an HTTP(S) `server` query
parameter. Watcher assets and gameplay can use separate ports. No automatic
retry: reconnect explicitly. Report local/shared mode, endpoint, known errors,
and available close details. An admitted OPEN socket remains authoritative even
after a transient message error; disconnected play uses a separately preserved
local sandbox, never a server character advanced locally.

Heartbeat sends WebSocket control Ping every 5 seconds with one pending
challenge. Matching control Pong before the 15-second monotonic deadline renews
liveness; gameplay messages and EDN diagnostic ping do not. Polling may add up
to 5 seconds under ordinary scheduling. Cleanup is idempotent and stale tokens
cannot affect replacements.

## Melee and knockback

There is no HP. A strike has 60 px center reach in the forward half-disk,
including the curved boundary but excluding the vertical line through the
attacker. Query once on the acceptance tick after all movement/collisions;
platforms do not occlude the query. Cooldown is 0.25 simulation seconds.

Each eligible hit adds 10 percentage points, without a 100% cap. Give accepted
strikes per-attacker sequence IDs and consume each strike once, including misses.
Independent simultaneous attackers select from the same resulting player map.
Resolve losses before selecting targets once ring-outs exist.

Trial impulse magnitude is `K = 200 + 3P` units/s, using the target's final
percentage after all hits that tick. Right-facing contributes `(0.8K, -0.6K)`;
left-facing contributes `(-0.8K, -0.6K)`. Sum simultaneous hits by axis. A first
hit at 10% gives `(184, -138)` to the right. New impulses add to total velocity
and retained received components; their movement begins on the next tick.

Received horizontal velocity is outside the voluntary 220 cap and decays toward
zero with friction independently of held direction. Gravity advances total Y;
a valid jump replaces total Y and clears received Y while preserving received X.
Contacts project both total and retained velocity to avoid restoring a canceled
impulse. Facing follows accepted intention, not launch velocity.

## Stocks, protection, and matches

- Three stocks per entrant, zero accumulated percentage, no HP.
- A complete ring-out consumes one stock exactly once. With stocks remaining,
  hide the body for 3 seconds; at zero, keep it eliminated until the next match.
- Respawn preserves ID/color/stocks and resets physical/combat state at stored
  spawn with 0%, one air jump, facing right, and locked controls until landing.
  The fresh body does not also move or consume actions on its return tick.
- Spawn protection lasts 2 seconds from match start/respawn, including the fall.
  Block both incoming percentage and impulse; consumed attacks do not replay.
  Protection expires before targeting at zero. It does not prevent ring-outs.
- Lifecycle is active/respawning/eliminated, with waiting introduced by match
  participation. Inactive input discards action pulses but may retain latest A/D.
  Stock loss does not release the connection slot.
- One connected entrant waits. Two to four trigger a 3-second countdown; falling
  below two cancels it. At expiry, freeze the contender roster and start everyone
  at fresh spawns with 3 stocks, 0%, 2-second protection, and a 180-second clock.
- Running/finished late joins observe with zero stocks and no body. They occupy
  capacity but never join the frozen roster or affect winner selection.
- Contender departures forfeit; preserve their last score/color in the roster.
  A replacement is a fresh observer, not a revived contender.
- Finish after a complete running tick if only one nonforfeited contender has
  stocks; respawning contenders count. None remaining is a draw. Also evaluate
  after trusted departure. Last-contender resolution precedes time-limit ranking.
- At time zero, rank by most stocks then lowest percentage. Exact best-score ties
  draw; stable ID ordering only orders display. Store result/reason/standings
  once, including departed contenders, without later reranking.
- Keep results for 5 processed seconds while gameplay freezes. Then reset current
  connections, including observers/eliminated, to waiting or a full countdown.
  Match ID increases only at actual start; room tick and connection IDs persist.
  Reset clears old results/actions/impulses and does not consume the new countdown.

## Target interface

Show player ID/color, percentage, stocks, lifecycle/respawn/protection, phase,
clock, and captured result/standings. Waiting/inactive records stay identifiable
without a body. Label the controlled body `Tú` and highlight its row neutrally.
Before a first shared snapshot, do not mislabel an offline fixture as the server
identity. Retain DOM rows and update only displayed changes. No local winner
calculation, physics prediction, or snapshot interpolation is part of this route.

## Abilities and recovery specification (planned, checkpoints 71–87)

The following rules define the planned extension tracked in pending checkpoints
71–87 of the [implementation roadmap](implementation-roadmap.md) (without
renumbering checkpoints 01–70). They are not yet implemented until their
respective checkpoints are delivered.

The established ability defaults are dash speed 400 units/s, nominal dash
immunity 180 ms and 2.5-second cooldown; Q duration 5 seconds and 10-second
cooldown; E reduction of 30% of the current percentage and 12-second cooldown;
R cooldown 13 seconds; and Nova radius 125 px with a full 100-point charge.
Additional values and interactions below are **trial balance choices**, including
the conversion of grenade/Nova hits to +35/+65 percentage points. They are
concrete defaults for a future implementation, not measured optimal balance.

### Shared activation, timing, and lifetime rules

- All players have the same abilities and parameters. Require an active,
  unlocked contender in a running match, or an active unlocked local-sandbox
  player. Waiting, respawning, eliminated, spawn-locked, and finished states
  reject requests without storing them for a later opportunity.
- Shift, Q, E, R, and F produce one request per fresh key press. Autorepeat and
  held keys cannot repeat activation. Blur/stop/replacement clears requests.
  The server checks eligibility and owns every effect and countdown.
- An accepted activation starts its full cooldown immediately. Do not decrement
  a new cooldown on that activation tick. Existing timers advance only through
  processed running simulation time, including during respawn; pause them in
  waiting/countdown/finished phases. Rejected requests consume no cooldown.
- Normal gameplay still samples hits at 25 ms boundaries. The nominal 180 ms
  dash immunity therefore expires at the first boundary at or after 180 ms:
  200 ms after activation at 40 Hz, with no early rounding to 175 ms. Other
  durations below are exact multiples of 25 ms. Do not add a separate timer.
- After existing timers advance, accept eligible Q and E before movement and
  damage. Resolve one requested locomotion action in order: a valid ledge
  release/climb, supported S drop, eligible W jump, then dash. A higher-priority
  rejected request does not block a lower-priority eligible action.
- Accept at most one requested offensive action per player per tick, choosing
  the highest eligible request: Nova, grenade, then melee. Ordinary jumps and
  attacks may coexist. An active dash, Nova windup, ledge hang, or ledge climb
  rejects all new ability/melee requests; these restrictions do not stop already
  active Q/protection timers or an already launched grenade.
- After movement and all ring-outs, resolve eligible melee hits and explosions
  from the same player snapshot. Each selected hit has a unique source event
  and affects each opponent once. Add all eligible percentage changes first;
  each impulse uses that target's final percentage for the tick. Sum impulses
  by axis and apply them after movement, as with basic melee.
- E applies before that tick's percentage additions, so healing can reduce the
  percentage used by a new hit; it never retroactively weakens an earlier
  impulse. A player losing its stock that tick cannot cast or receive a hit.
- Spawn and dash immunity block both percentage and new hit impulse. Q blocks
  only the new impulse. Immunity has precedence over Q. Immune misses are
  consumed and produce no delayed effect, charge, or elimination attribution.
- Activating R or F ends existing spawn protection before targeting that tick.
  Ordinary melee keeps its specified spawn-protection behavior. Q and E do not
  end spawn protection. Nova and grenade grant no new immunity of their own.
- Extend exact input validation when ability intentions are first captured, and
  snapshot validation only when the corresponding state is implemented. Send
  boolean action intentions, not effect values or claimed
  charge/cooldowns. Store serializable effects/projectiles/timers in the world;
  keep all ownership tokens private and reuse the existing room queue.

### Dash — Shift

- **Controlled horizontal speed:** 400 units/s in the stored facing direction,
  locked at activation. **Duration:** 0.20 seconds, or 8 ticks. At neutral received
  velocity with no obstacle, the dash covers 80 px.
- **Cooldown:** 2.5 seconds from acceptance. **Incoming-hit immunity:** nominal
  0.18 seconds, quantized as described above. The activation tick is the first
  of eight dash movement/hit-evaluation ticks; immunity covers hits in all eight
  and expires before hit evaluation in the ninth. Movement duration counts
  those same eight steps, including activation. A collision can stop travel,
  but cannot shorten or extend the immunity deadline or dash input lock.
- Replace the voluntary horizontal component with dash speed for the duration;
  do not add 400 every tick. Existing received X remains separate and decays
  normally, so total speed can differ from 400. Preserve vertical velocity,
  gravity, and the remaining air-jump allowance. Air dashing is allowed.
- Ignore steering, jumps, drops, attacks, and new ability requests while the
  dash is already active. No cancellation or action buffering. Accepted Q/E on
  the activation tick may coexist because their effects precede locomotion.
- Sweep ordinary solid contacts. A solid contact whose normal has a nonzero
  X component stops dash travel, resolves total/received motion, and consumes
  remaining movement for that tick. Keep the input lock and immunity until
  their original deadlines; do not allow an early collision to enable attacks
  during the remaining immunity. Later steps can resume gravity/contact motion
  with zero voluntary X, but cannot restart dash travel. Flat top support does
  not cancel travel.
  Semisolid surfaces retain their normal one-way rules. Dash cannot bypass
  blast bounds, deal contact damage, pass through solids, or reset facing.
- After it ends, normal movement resumes. Clamp only the voluntary X component
  to the normal 220 cap before ordinary acceleration/friction on the next tick;
  preserve separately received motion. Do not leave permanent dash-speed drift.

The 80 px displacement exceeds melee reach enough to create separation without
crossing the arena in one activation. Immunity cannot be converted into an
attack during the dash itself.

### Antiknockback — Q

- **Active duration:** 5 seconds, or 200 ticks. **Cooldown:** 10 seconds, or
  400 ticks, from acceptance; this leaves 5 seconds without Q if reused as soon
  as possible. No stacking, refresh, or repeated activation while held.
- Every otherwise eligible melee/grenade/Nova hit still adds percentage.
  Suppress that hit's complete new impulse on both axes. Consume and attribute
  the hit normally; actual percentage gain may contribute Nova charge.
- Do not cancel existing received velocity or reset a current fall. Gravity,
  voluntary movement, platform response, blast bounds, and stock loss continue
  normally. Q prevents new launching, not the consequences of earlier hits.
- Q expires before hit eligibility is evaluated at zero. Incoming impulses
  apply immediately on that tick. Ring-out/respawn clears the active Q effect;
  its per-match cooldown continues rather than resetting.

Keeping percentage gain makes prolonged Q use increase vulnerability after the
protection expires. A neutral dashed outline and remaining-seconds label
identify it without introducing a colored status effect.

### Percentage recovery — E

- **Effect:** immediately set `P = max(0, P * 0.70)`. **Cooldown:** 12 seconds,
  or 480 ticks. Examples: 80% becomes 56%; 20% becomes 14%; 0% stays 0%.
- This is a relative reduction, not subtraction of 30 percentage points. Keep
  full calculation precision in simulation; round only displayed percentages
  to one decimal, omitting a trailing `.0`. Winner comparisons use the stored
  value, not the rounded label.
- At exactly zero percentage, reject E without spending cooldown. A successful
  E restores no stock, changes no velocity, and grants no immunity, air jump,
  charge, or cooldown reset. It does not erase an attacker's attribution window.
- Acceptance is instant, without channeling or input lock. It can combine with
  Q, a jump, a dash beginning that tick, or one eligible offensive action.

### Impulse grenade — R

- **Cooldown:** 13 seconds, or 520 ticks. At most one live grenade per owner.
  Spawn its radius-6 circle at `(player-x + facing-sign * 22, player-y - 8)`.
- **Initial velocity:** `(facing-sign * 320, -260)` units/s. **Projectile
  gravity:** 600 units/s². **Maximum lifetime:** 1.5 seconds, or 60 movement
  ticks after creation. Aim follows stored facing; no mouse-aim protocol is
  required. A new projectile neither moves nor ages on its creation tick;
  its first flight interval is the following tick.
- Detonate once on the earliest swept contact with an active opponent body,
  any solid surface, a descending semisolid top/upper endpoint, or at lifetime
  expiry. Sweep the grenade against opponents' actual piecewise movement paths
  over the same tick, including contact corrections, rather than treating a
  final body position as stationary. Consider only opponents still active after
  this tick's ring-outs; bodies that already lost a stock do not trigger it.
  Immunity and Q do not change physical trigger eligibility. Query explosion
  damage at final player positions, matching the shared post-movement snapshot.
  Use stable event/platform/player IDs to break exact contact-time ties.
  Rising traversal and semisolid sides/undersides remain passable. No bouncing,
  piercing, cooking, remote trigger, or chain reactions.
- A protected opponent still physically triggers detonation, but receives no
  hit effect. The owner never triggers its own grenade and is excluded from
  its explosion. Other eligible opponents are independent targets.
- **Explosion radius:** 100 px, measured inclusively between explosion center
  and target center. **Accumulation:** +35 percentage points per target.
  There is no extra direct-hit percentage in addition to the explosion.
- **Trial impulse:** `K = 300 + 4P`, using final post-hit percentage. Scale K by
  `1 - 0.4 * d / 100`, where d is target-center distance within the blast. This
  gives full impulse at the center and 60% at the outer edge; percentage stays
  +35 throughout the radius. A lone hit on a target starting at 0% uses P = 35
  and K = 440 before distance scaling.
- Use the radial direction and upward bias defined below. Platforms do not
  occlude the explosion query. Hits still obey spawn/dash immunity and Q.
- A strict whole-circle blast-bound exit uses the grenade's radius 6, not
  just its center. Compare its exit time with contact/expiry times: an earlier
  exit removes it without detonation, and exact exit ties take precedence.
  Owner stock loss, owner departure, or match finish also removes it without
  detonation. Projectiles belong to their
  original player ID; reusing a color cannot transfer them to a new session.

### Nova — F

- **Requirement/cost:** exactly 100 charge points, consumed on acceptance.
  A fresh match starts at 0; no passive charge is generated. F below full
  charge is rejected without reserving a later cast.
- **Windup:** 0.25 seconds, or 10 later ticks, shown as an expanding neutral
  outline. Normal movement/gravity and received motion continue during windup,
  but jumps, drops, melee, dash, and new power requests are discarded. Taking
  a hit does not cancel windup or prevent the resulting displacement.
- At expiry, detonate once at the owner's current center after movement only
  if that owner is still active and eligible. Ring-out, departure, or match
  finish cancels the cast without a charge refund. No additional cooldown is
  required because another cast needs a newly filled meter.
- **Radius:** 125 px between centers, inclusive. **Accumulation:** +65 percentage
  points per eligible opponent; never hit the owner. No line-of-sight check.
- **Trial impulse:** `K = 400 + 4P`, multiplied by `1 - 0.4 * d / 125`. A lone
  hit on a target starting at 0% uses P = 65 and K = 660 before distance scaling.
  Use the same direction, immunity, Q, and single-resolution rules as grenade.
- **Charge generation:** +0.5 charge per percentage point actually dealt by
  melee/grenade, capped at +20 damage-based charge per source event; +0.2 per
  percentage point actually received from melee/grenade, capped at +5 received
  charge per tick; +15 per credited elimination. Clamp the meter to [0, 100].
  Melee can contribute 5 charge per opponent hit; one grenade target gives 17.5.
- Nova percentage/impulse effects grant no damage-based charge to either side;
  a credited elimination can still grant +15. Healing, blocked hits, self-falls,
  forfeits, waiting, and time passing grant none. This prevents an explosion
  against several opponents from immediately funding another Nova.

### Explosion direction and simultaneous effects

For each blast target, let `(dx, dy)` point from explosion center to its center,
with `d = sqrt(dx*dx + dy*dy)`. For nonzero d, normalize
`(dx, dy - 0.35*d)` and multiply by the scaled K. This is a radial launch with
an upward bias: same-height targets leave upward, while targets well below the
blast can still launch downward. If d is zero, use the owner's facing vector
`(0.8*facing-sign, -0.6)` and full K; never divide by zero.

A source event applies once to each selected opponent, even across several
frames. Simultaneous melee/grenade/Nova hits share one final percentage and sum
impulses; do not resolve by player-map iteration order. Q can therefore receive
all eligible percentage additions while producing zero new impulse. Dash/spawn
immunity removes both contributions from otherwise geometrically valid hits.

### Main-platform ledge recovery

- Only the two upper corners of `:main` are grabbable: `(170, 280)` and
  `(630, 280)`. No semisolid or underside grabs. Require active unlocked state,
  nonascending motion (`vy >= 0`), and no dash/Nova windup/drop exception.
- **Capture reach:** center within 32 px of a corner, with center Y at or below
  its top and no more than 32 px below it. For left/right respectively, center
  X must be at or outside `left - radius` / `right + radius`. Evaluate a swept
  approach so high speed does not skip the capture area. Hold toward the
  platform, or neutral, to accept a grab; holding away prevents it. Evaluate
  capture as part of movement, before ring-out checks, along the actual
  collision-resolved outside trajectory. The earliest eligible capture
  truncates remaining travel and anchors the body; do not use a path through
  a solid that contact resolution already blocked.
- Anchor at `(154, 296)` on the left or `(646, 296)` on the right. Clear motion
  and suspend body gravity while hanging. **Maximum hang:** 1 second, or
  40 later ticks. Grant no hit immunity, percentage reduction, stock, or air
  jump. One grab is allowed between supported top landings.
- Fresh W begins a **0.20-second climb**: first move up outside the side for
  0.10 seconds, then across at supported-top height for 0.10 seconds. Finish at
  `(186, 264)` or `(614, 264)` only if the path/destination is clear. Completion
  is a real supported top landing and restores the normal air-jump allowance.
  Do not clip through platform geometry or teleport through another obstacle.
- Fresh S, held movement away, or hang expiry releases a hanging body from
  the anchor with zero initial vertical velocity; normal gravity resumes. S
  has priority over W. During a climb, S or held movement away also cancels it
  before advancing that step's climb path: release from the current position,
  never snap back to the anchor, clear voluntary motion, and resume normal
  physics once. A successful unblocked hit releases hang/climb and applies its normal
  percentage/impulse. Q-blocked impulse alone does not release the anchor.
- Hanging/climbing cannot initiate melee, Q/E/R/F, or dash. Existing timers
  continue; hit protection follows its normal rules. Climb remains vulnerable.
  Blocked climb keeps hanging while its original hang deadline continues.
  A started climb may finish even if that hang deadline expires during climbing.
- Only a later supported top landing restores grab availability. Death/reset
  clears ledge state. This prevents repeated drop/regrab from stalling a match;
  initial spawn still cannot use a ledge to bypass the first-landing lock.

### Cross-life resets, attribution, and presentation

Power cooldowns and Nova charge are per-match values associated with a player
ID, separate from its resettable physical body. Preserve charge across a stock
loss; keep decrementing existing cooldowns during the running respawn delay.
Loss clears active dash/Q/cast/ledge state and removes owned grenades. Respawn
still resets core physical/melee state and grants its specified spawn lock and
protection. A fresh match resets power cooldowns and charge to zero; a new
connection never inherits either. On match finish, remove grenades and cancel
pending Nova casts without refunds, then freeze the remaining character-effect
and cooldown timers with the result. A canceled cast/projectile has no remaining
live timer to freeze; final snapshots must not imply it can detonate later.

**Trial elimination attribution:** record the owner selected from the latest
tick in which eligible opponent hits actually increased percentage, plus that
tick's simulation time. Within one simultaneous hit tick, choose the opponent
whose hits added the most percentage in total; an exact tie chooses the smaller
numeric player-ID suffix. This tie rule is solely for attribution, never victory
ranking. An impulse-blocked Q hit qualifies; an immune hit does not.

On ring-out, award one elimination if the recorded tick is at most 8 processed
seconds (320 ticks) old and its selected owner is still a nonforfeited contender.
Otherwise record a self-fall. Do not fall back to an older attacker if that
selected owner forfeits. E, ledge capture, or Q activation does not clear this
history; respawn does.

Compute credits from the common pre-loss roster and this tick's eligible hit
history, then commit all losses/credits together. A last-stock hit attacker can
receive credit even if it loses its own last stock that tick. Ring-out detection
still precedes new targeting: a player already exiting this tick cannot acquire
credit from an otherwise pending new strike. Forfeits award neither elimination
nor self-fall. No assists or elimination-based victory tiebreak are introduced.

Store eliminations and self-falls per contender for the match and capture them
with final standings. Winner selection remains stocks first, then lowest
percentage; exact best-score ties still draw. Any future score system requires
its own specification rather than silently changing that order.

Show compact Shift/Q/E/R readiness and remaining cooldown, Q duration, dash or
Nova windup state, Nova charge from 0 to 100, and eliminations/self-falls beside
existing player information. Use small neutral labels, bars, and solid/dashed
outlines; no colored ability effects, projectiles, status backgrounds, or
platform accents. Only player bodies and identity swatches retain hue. Render
shared snapshot values and update only changed displayed fields. A down or
waiting player keeps its cooldown/charge labels with the appropriate role.

### Trial balance and future acceptance checks

Keep these values in named configuration, independent of player color. Before
calling the extensions balanced, exercise two- and four-player sessions and
measure time to eliminate, useful recovery distance, Q uptime, successful E
uses, grenade hit rate, and Nova frequency. The roadmap's final documentation
checkpoint adds this manual checklist; listing it does not claim balance.

Verify especially: cooldown boundaries and nonrepeating keys; active requests
across lives; no E stock/velocity change; no retained knockback erased by Q;
no dash tunneling; single grenade detonation; one Nova resolution; multi-hit
final-percentage order independence; immunity/Q/charge interactions; swept
ledge capture and no indefinite regrabbing; attribution cutoff and simultaneous
losses; identical snapshots; and readable monochrome rendering.

## Scope boundary

Checkpoints 01–70 of the roadmap cover the completed core local/shared game and
presentation polish. Pending checkpoints 71–87 plan the abilities, ledge
recovery, ability HUD, attribution, and trial-balance extension. Do not
implement pending checkpoints out of order or renumber 01–70. Character-specific
stats, additional victory scoring, art assets, touch controls, and external
automated opponents remain outside both scopes. This document provides the
specification for planned checkpoints; it does not mark any pending feature
implemented.
