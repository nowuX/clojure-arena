# Simulation verification

This guide lists local checks for the movement checkpoint. A command completing successfully is evidence for that command's boundary; it does not establish native browser behavior. No native-browser result is implied by this checklist. See the [development guide](development.md) for prerequisites and command details.

## Setup and automated checks

The documented prerequisites are Java 21+, Clojure CLI, Node 22+, pnpm 12.17.0, and `just`. Install the pinned dependencies when needed:

```sh
just setup
```

Run deterministic simulation and client-boundary tests in Node:

```sh
just test
```

The recipe compiles the Shadow test target and runs `.shadow-cljs/test.js`. Use the Node suite for reproducible checks of the 25 ms step, acceleration and neutral braking, direction reversals, input clearing on blur/stop, fixed-step accumulation, and platform face/corner contacts. The client fixtures use `EventTarget` and controlled animation-frame callbacks; those doubles do not exercise a browser's native focus handling or Canvas rasterization.

Compile an isolated release when checking the production compiler path:

```sh
just build '{:output-dir "/tmp/clojure-arena-release/js"}'
```

This form keeps the normal watched output separate. A successful build checks compilation only.

## Native browser checklist

On the service host, start the local page and open the displayed address in a native browser:

```sh
just dev
```

The default address is `http://127.0.0.1:8081/`.

- Confirm the Spanish ready status reads `Arena lista.` and the scene shows the dark background, neutral platform, and colored player.
- Let the player fall onto the platform. Hold D and then A; confirm horizontal speed builds toward the direction of the key.
- Release a direction and confirm the player slows over several ticks rather than stopping instantly. Hold A and D together and confirm the result is neutral braking.
- While holding a direction, move focus away from the page. After blur, the held direction should no longer accelerate the player; neutral friction should slow any remaining motion.
- Move along the platform and off an edge. Confirm the player falls after leaving the top support. Automated fixtures cover controlled side, underside, and rounded-corner approaches.
- Reload and repeat startup/stop behavior if the page is reinitialized by the development workflow; confirm there is no duplicated input response or animation loop.

These are manual checks to perform and record in a browser; they are not results from this documentation preparation. The Node suite cannot verify browser event ordering, real focus transitions, native `requestAnimationFrame` cadence, rendered Canvas pixels, or access from another device. There is no networked gameplay service in this checkpoint.
