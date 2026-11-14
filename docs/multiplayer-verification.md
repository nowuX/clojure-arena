# Multiplayer verification

This is a reproducible checklist, not a claim that native-browser or remote
device checks have run. Automated tests use real JVM sockets on the service
host and Node browser doubles.

## Start one server

Follow [Development](development.md). For a built page, run `just build` then
`just server <HOST> 8082` and open `http://<HOST>:8082/` in all pages. For watched
assets use `http://<HOST>:8081/?server=http://<HOST>:8082` with `just dev <HOST>`
in a separate terminal. Obtain the service machine's Tailscale IP using
`tailscale ip -4` for another device; use that IP consistently.

1. Open two pages. Both should say «Arena compartida conectada.» with distinct
   IDs/colors and show exactly those two bodies and panel rows. Four local
   fixtures or a local-fallback notice means the shared session is not active.
2. Wait for the first supported landing. Move one page with A/D, jump with
   fresh W presses, drop from an outline using S, and click inside the canvas.
   The other page should display that movement and hit effects. Only the
   originating page's assigned player responds to its controls.
3. Bring two players within 60 px, face the opponent and primary-click.
   All pages should show +10 percentage points on that target and the same
   knockback trajectory. Repeated valid strikes require 0.25 simulation
   seconds. Ordinary movement should match even on pages with different RAF
   rates; no client predicts physics.
4. Open two more pages. Confirm four unique IDs/colors and the same four bodies.
   Open a fifth: it should retain «La sala está llena; el juego continúa en modo
   local.» and control only its local sandbox. Accepted pages remain unchanged.
5. Close one accepted page. Its row/body should disappear from survivors.
   Explicitly reload the rejected page after cleanup: it should enter with a
   larger ID, available color, 0%, fresh motion and locked spawn. No automatic
   retry or cookie-based resume occurs.
6. Hold a direction and lose focus: movement intention becomes neutral. Reload
   that page and verify no old jump, attack, percentage or velocity reappears.
   Tabs sharing cookies still receive separate connection identities.
7. Stop the game server. Pages return to their separate offline worlds without
   simulating the last network snapshot. Restart it and reload explicitly.
8. If a second device is available, repeat admission/movement there. Verify
   `http://<TAILSCALE_IP>:8082/health` and the page from that device. A host-only
   response cannot establish its connectivity. For silent loss, disconnect a
   device's network without closing its page and verify its row disappears
   after the heartbeat deadline is observed (15 s deadline, 5 s pulse cadence).

## Automated evidence boundaries

`just check` runs JVM/shared simulation, Node and isolated release checks.
JVM tests exercise concurrent admissions, owner-only inputs, same-tick worlds
and melee across four real sockets, fifth rejection, delayed publication,
heartbeat challenges/deadlines, closure, cookies, reloads and color reuse.
Room-agent tests interleave input/tick/join/leave and verify captured recipients
and world consistency. Servers use ephemeral ports and are stopped in `finally`.

Node fixtures verify event delivery before RAF, taps/autorepeat/blur, lifecycle
cleanup, terminal notices, stale callback/snapshot rejection, and one captured
world for Canvas/panel with separate offline fallback. They do not render a
native browser or emulate physical network loss. The release checks compiler
compatibility only. Native focus, appearance and cross-device behavior require
the checklist above.
