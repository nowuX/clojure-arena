# Shared matches

The room world contains public `:match` data, separate from transport
membership and the monotonically increasing room tick. A match has an ID,
phase, contender map, countdown/running/intermission timers and captured result. Connection tokens never
enter this map. New connections own waiting player records with zero stocks;
waiting records have no body, physics or combat. Waiting ticks still publish
snapshots and consume one-shot inputs without declaring a lone player winner.

The offline sandbox continues to call `game/step` directly. Connected rooms
call `match/step`; only a running match advances the existing pure simulation.
Phase transitions, ranking and resets are pure transformations of this same world.

## Shared start

The second admission starts a full three-second countdown; joins during the
countdown enter the same start. Dropping below two cancels it. On the 120th
later 25 ms tick, the room captures current entrants, increments the match ID
and rebuilds factory state with three stocks and two full seconds of protection.
That start tick performs no physics. Waiting/countdown one-shot inputs are
discarded; the latest horizontal direction can take effect only after landing.

## Late arrivals

Admission still uses a free slot and a fresh ID/color. During running or
finished phases it adds a waiting observer with zero stocks, without changing
the fixed contender roster. Running simulation receives only nonforfeited
roster players, then merges their results into the connection-owned player map.
Observer one-shot actions are discarded and cannot affect the current match.
Observers still count toward the four-connection capacity.

## Trusted departures

`remove-player` captures a departing contender's latest color, stocks and
percentage and marks its roster entry forfeited before ordinary room cleanup.
Disconnect and timeout use the same trusted leave path. An observer departure
only removes ownership. Reused colors receive a new ID and do not restore the
old contender. Departure does not charge a stock or grant elimination credit.
A contender departure also checks whether the match is decided.

## Running clock

A start installs 180 seconds. Only subsequent running ticks subtract their
processed simulation dt and clamp tiny remainders to zero: 7,200 ticks at 40 Hz.
Waiting, countdown and finished phases hold gameplay time. Scheduler pulses
that are dropped under overload do not advance simulation time. Rendering has
no authority over this clock. At zero, resolve the completed running tick.

## Last-contender results

After movement, exits, hits and roster score capture, nonforfeited contenders
with remaining stocks stay eligible, including those currently respawning.
One wins; none produces a draw. Trusted contender departure uses the same
resolution. `result-for` calculates facts and `finish-if-decided` captures the
result once. Finished ticks freeze physics, attacks, lifecycle/protection and
the running clock while transport ticks and snapshots continue. Later departures
may update eligibility metadata but never rewrite the captured result.

## Time-limit ranking

Expiry is resolved after that tick's physics, losses and hits. Last-contender
resolution takes priority. Otherwise rank by remaining stocks descending, then
percentage ascending. An exact best-score tie draws. Forfeits sort last;
player ID is only a stable display order and cannot break a score tie.
The result captures ordered standings, winner ID/color and reason. Validation
rechecks those captured facts, preserving them even if the winner later leaves.

## Read-only presentation

Canvas omits waiting and observer bodies. Player rows retain all current
connections, distinguishing waiting, countdown, observers and eliminated
contenders. The match panel displays the shared countdown, an authoritative
mm:ss clock and the committed winner/draw/reason and ordered standings.
Winner metadata comes from the captured result, even after disconnection;
rendering never computes a local result. Text and states remain neutral, with
identity colors restricted to bodies and small swatches.

## Repeated matches

A result installs a full five-second intermission. Only later finished ticks
consume it. On the 200th later 25 ms tick, rebuild all current connections as
fresh waiting records and clear the old result/roster. With at least two
connections enter a full three-second countdown without consuming it on that
reset tick; otherwise wait. Observers and eliminated players enter the next
start with three stocks. Each start increments the match ID, while IDs, colors,
sockets, the room tick and the single room clock survive match resets.
One-shot actions never replay; retained horizontal input remains subject to
the fresh body's landing lock. The panel shows the shared intermission timer.

## Snapshot and command contract

`:match` has exact fields `:id`, `:phase`, `:countdown-seconds-left`,
`:time-seconds-left`, `:intermission-seconds-left`, `:result` and `:contenders`.
Each contender stores color, stocks, accumulated percentage and a forfeit flag.
A result contains reason, nullable winner ID/color and an ordered standings
vector. Waiting/observer records keep their connection-owned IDs in `:players`.
The roster remains fixed until reset, including captured forfeited records.

Only trusted room actions change this state. Clients still send exact input
and ping frames; they cannot issue start, winner, clock, reset, tick or roster
commands. Schema validation checks phase/timer consistency, roster-to-player
scores and captured result ordering without evaluating received EDN.

## Trace one complete cycle

A running exit consumes one stock before combat. If stocks remain, later ticks
count three seconds, rebuild a fresh locked body and grant two seconds of
protection. Otherwise the body stays eliminated. Capture updated contender
scores and decide last-contender victory before considering clock ranking.
Freeze the committed result for the five-second intermission, then rebuild
current connections and start another full countdown. Room snapshots continue
through every transition, including waiting and observers.
