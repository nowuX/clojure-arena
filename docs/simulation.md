# Simulation

The shared simulation is `src/clojure_arena/game.cljc`. Its functions receive values and return a new world; keyboard, clock, DOM, and Canvas effects belong to `client.cljs`.

## World and movement

`initial-world` contains one solid, three visible semisolid entries, and one player.
Descending players land on semisolid tops and upper endpoint arcs; rising,
side, and underside movement passes through.
The main entry and player are:

```clojure
{:platforms [{:id :main :type :solid :x 170 :y 280 :width 460 :height 16 :color "#9c9c9c"}]
 :player {:x 400 :y 160 :vx 0 :vy 0 :radius 16
          :spawn-locked? true :air-jumps-left 1 :color "#22d3ee"}}
```

The background is `#101010`; the platform is neutral gray and only the player body is colored. Gravity is `980` units/s² downward. Horizontal intention targets ±220 units/s at up to 880 units/s². Neutral input applies `horizontal-friction` at 1100 units/s² toward zero, without reversing velocity. At the client's 25 ms tick those changes are at most 22 and 27.5 units/s respectively. A locked spawn forces horizontal intention to zero while gravity and collision continue. From rest, ten held-direction ticks reach 220; from 220, neutral braking stops in eight ticks and travels another 19.25 units in free space.

`step` receives a world, `{:horizontal ... :jump? ...}`, and `dt` in seconds. A missing direction defaults to zero and a missing jump action to false. Its order is:

1. While `:spawn-locked?` is true, replace horizontal intention with zero.
2. Call `apply-jump` before gravity. A locked player rejects the request. An unlocked supported player launches with `vy = -420` and keeps the air-jump allowance. An unlocked airborne player launches with `vy = -420` only if `:air-jumps-left` is positive, then spends one. Requests with no allowance are rejected.
3. Accelerate or brake horizontally, add `980 × dt` to `vy`, then call `collide-platforms`.

From a supported center Y of 264, the first launch tick ends at Y = 254.1125 with vy = −395.5. Holding W does not change jump height. Each fresh W press is one request. A grounded jump and the single allowed air jump both replace vertical velocity with -420 before gravity is added.

## Support, spawn, and allowance

`grounded?` is true when vertical velocity is zero, the circle center lies within the platform's horizontal span, and its feet meet the top within `contact-epsilon`. `supporting-platform` receives the platform vector and returns the supporting entry, otherwise `nil`.

The initial player falls with controls locked. A resolved supported top landing sets `:spawn-locked?` to false and restores `:air-jumps-left` to one. Side, underside, and corner contacts do neither. A ground jump preserves the allowance; walking off an edge also preserves it. An accepted air jump reduces it to zero, and a later supported landing restores it. `apply-jump` checks support at the start of the tick, so an action rejected during the landing tick is not buffered for after landing.

## Contact and integration

`move-player` advances the circle center using velocity and seconds. `supported-travel-time` bounds motion along a supported top and lets gravity begin after leaving an edge. `earliest-contact` chooses the earliest contact across eligible platform surfaces, using stable ID order for exact time ties. `platform-contact` checks overlap and swept contact against each rectangle's faces and rounded corners; `resolve-contact` removes only inward normal velocity. `collide-platforms` allows up to four contacts per tick and uses a `1e-9` tolerance.

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

S captures a separate `:drop?` request with the same fresh-press lifetime as W.
`read-inputs` preserves both flags; `consume-inputs!` clears both action pulses
and retains held A/D. Blur and lifecycle cleanup clear every pending action.
At this stage `step` ignores the drop request.
