# Simulation verification

A Node pass or release compile checks only its own boundary. The browser checklist below is manual guidance, not a report that native-browser checks have run. See the [development guide](development.md) for prerequisites and command details.

## Node and build checks

The project documents Java 21+, Clojure CLI, Node 22+, pnpm 12.17.0, and `just`. Install dependencies when needed, then run the Node regressions:

```sh
just setup
just test
```

`just test` compiles the Shadow test target and runs `.shadow-cljs/test.js`. Use deterministic fixtures for acceleration/friction, spawn lock, support, jump eligibility and allowance, action consumption, frame-cadence equivalence, focus/cleanup, and face/corner contacts. Client fixtures use `EventTarget` and controlled animation-frame callbacks; they do not verify native focus handling or Canvas pixels.

To check the release compiler path with isolated output:

```sh
just build '{:output-dir "/tmp/clojure-arena-release/js"}'
```

A successful release compile does not establish browser startup or appearance.

## Native browser checklist

Start locally with `just dev` and open `http://127.0.0.1:8081/` on the service host.

- Confirm `Arena lista.` appears with the dark background, filled main platform, three neutral outlined semisolids, and colored player.
- On first fall, hold D and press W. The player should stay at its spawn X and should not jump. After landing on the platform top, controls unlock; the earlier W request must not replay. A held D direction should begin moving on the following tick.
- Hold D, release it, then reverse with A. Check acceleration and gradual braking. Hold both keys together and check neutral braking.
- Walk off either platform edge and confirm falling resumes. A fresh W press after leaving an edge can use the single air jump. Reload to restore the initial scene after falling out of view.
- Release W, then make a fresh W press while grounded. Confirm a ground jump. While airborne, make another fresh W press and confirm one air jump. A third fresh press must not launch; it must not be saved for later.
- Land on the platform and confirm a fresh W press can launch again. This checks that supported landing restores the air-jump allowance.
- While holding a direction or with a W request pending, move focus away from the page. Confirm blur clears both held directions and the pending jump. Repeat startup/stop through the development workflow and check for duplicate input response or animation loops.

A browser can verify event ordering, focus behavior, native `requestAnimationFrame`, and visible Canvas output. Node doubles cannot establish those behaviors. Use deterministic automated fixtures for side, underside, and corner-contact constructions that are not practical to reproduce through this local play loop.

## Multiple-platform checks

- Move toward a side outline and jump through it from below. On the descent,
  land on its top and make another ground jump. Use the extra air jump to
  reach the upper outline.
- Leave each semisolid slowly and at full speed from either edge. The circle
  should start descending around the upper endpoint rather than penetrating
  the rectangular platform or catching a side wall.
- Automated fixtures isolate the main platform for earlier movement tests;
  semisolid and scene tests exercise the complete four-entry scene separately.
