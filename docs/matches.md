# Shared matches

The room world now contains public `:match` data, separate from transport
membership and the monotonically increasing room tick. A match has an ID,
phase, contender map and timer/result placeholders. Connection tokens never
enter this map. New connections own waiting player records with zero stocks;
waiting records have no body, physics or combat. Waiting ticks still publish
snapshots and consume one-shot inputs without declaring a lone player winner.

The offline sandbox continues to call `game/step` directly. Connected rooms
call `match/step`; only a running match advances the existing pure simulation.
Countdown, result resolution and repeated play are introduced by later steps.
