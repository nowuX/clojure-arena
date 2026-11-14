# Local combat

## Strike acceptance

The canvas queues one `:attack?` intention. `step-player` calls `step-melee`
before movement and before a landing can unlock spawn. Each player begins with
`:melee-strike-id 0` and `:melee-cooldown-left 0`. Decrement the cooldown by dt,
clamp at zero within `cooldown-epsilon` (1e-9), and accept a fresh request only
when unlocked and ready. Acceptance increments that player's sequence and sets
0.25 seconds. At 25 ms per tick, the tenth later tick can accept another strike.
Rejected clicks are consumed, never stored until readiness. Misses still spend
the cooldown. Acceptance currently has no target or hit effect.
