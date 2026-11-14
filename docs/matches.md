# Shared matches

The room world now contains public `:match` data, separate from transport
membership and the monotonically increasing room tick. A match has an ID,
phase, contender map and timer/result placeholders. Connection tokens never
enter this map. New connections own waiting player records with zero stocks;
waiting records have no body, physics or combat. Waiting ticks still publish
snapshots and consume one-shot inputs without declaring a lone player winner.

The offline sandbox continues to call `game/step` directly. Connected rooms
call `match/step`; only a running match advances the existing pure simulation.
Result resolution and repeated play are introduced by later steps.

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
old contender. Departure does not charge a stock or grant elimination credit;
result resolution is added separately.
