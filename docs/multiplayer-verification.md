# Multiplayer verification

This checklist describes native-browser and remote-device checks to perform.
Automated evidence uses JVM loopback sockets and Node browser doubles; neither
establishes native appearance, physical network loss or access from another device.

## Start one server

Follow [Development](development.md#run-the-shared-arena) for one shared
server, watched assets and the service machine's Tailscale address. All pages
must target that same gameplay endpoint.

## Shared match checklist

1. Open one page. It owns one ID/color and a waiting row with zero opportunities;
   no body falls and no lone winner is declared. Open a second page: both show
   the same three-second countdown. Close it before expiry to cancel; reconnect
   to get a fresh full countdown.
2. At the start, both get three opportunities, 0%, locked spawns and a two-second
   neutral ring. Wait for landing and protection expiry, then use A/D, fresh W
   presses, S on an outline, and primary click inside Canvas. Only the originating
   page's assigned player responds. Both pages show the same movement and hits.
3. Face an opponent within 60 px and click. Both pages show +10 percentage points
   and the same impulse. Repeated valid strikes require 0.25 simulation seconds.
   Blur neutralizes held direction; held keys do not replay discarded actions.
4. Open two more pages during running. They occupy unique IDs/colors and appear
   as observers with zero opportunities and no bodies, without altering the roster.
   A fifth connection keeps its full-room notice and controls its local sandbox.
5. Move a complete circle outside the blast bounds. Both pages show one lost
   opportunity, absent body and three-second respawn. At return verify 0%, original
   spawn, locked controls and a fresh two-second ring. Partial exits do not charge
   a life; protected players can still fall. Repeat through permanent elimination.
6. When one eligible contender remains, including a respawning one, show the same
   captured winner and ordered standings. Simultaneous final eliminations draw.
   Observers cannot win. Check that finished physics and the running clock freeze.
7. On the authoritative clock reaching zero, verify more stocks win before lower
   percentage, and exact best-score ties draw. Final-tick hits/exits affect ranking;
   last-contender resolution takes priority. The panel never decides locally.
8. Keep the result for a full five-second intermission. Current connections,
   including observers/eliminated players, reset into a fresh countdown. Verify
   new match ID, unchanged connection IDs/colors and no stale movement/attacks.
   With fewer than two current connections the room waits instead.
9. Close a running contender. Capture it as forfeited and check the remaining
   contender can win. Closing an observer does not alter eligibility. Reload gets
   a larger fresh ID; color reuse never revives old state. A winner that leaves
   after finish remains visible in the captured result until reset.
10. Stop the gameplay server. Each page restores its own offline world. Restart
    and reload explicitly. For silent network loss, verify the record is retired
    after the heartbeat deadline (15 s deadline, 5 s probe cadence), using the
    same forfeit path as ordinary closure.
11. From another device, verify `http://<TAILSCALE_IP>:8082/health` and the page,
    then repeat shared controls and match transitions. A host-only response does
    not establish that device's connectivity.

## Automated evidence boundaries

`just check` runs all JVM tests, Node tests and an isolated optimized build.
Pure tests cover exact 119/120 start/respawn, 79/80 protection, 7,199/7,200 clock
and 199/200 intermission boundaries, score ties and repeated fresh matches.
Room-agent tests race input/tick/join/leave at finish, reset and start boundaries,
checking every captured public world and ownership invariant.

Owned port-zero WebSocket tests compare same-tick snapshots across clients through
stock loss, full respawn/protection, elimination victory, observer admission,
forfeit victory, intermission, rematch and a time-limit draw. They control trusted
test state/time; no privileged match command is exposed to clients. Existing
checks retain four-client melee, fifth rejection, delayed publication, heartbeat,
reload and cleanup coverage. Servers close in `finally`.

Node doubles cover event/RAF ownership, raw waiting/countdown/result snapshots,
neutral result text, a departed winner, ordered standings, observer rows and
safe offline fallback. They do not inspect native Canvas pixels, browser focus
or a second device. The release check establishes compiler compatibility only.

## Connection and ownership checklist

- Check that the status names local/shared mode and the attempted WebSocket
  endpoint. A watcher-only page stays local. An invalid server query reports
  configuration failure; an unavailable server reports a WebSocket error
  without claiming an unobservable DNS, firewall or TLS cause.
- While accepted but waiting for the first snapshot, no row or body should
  claim local ownership. After receipt, `Tú` and the accessible current row
  must follow the assigned ID, including after color reuse or reload.
- Full-room rejection and expiry remain visible through closure. Where the
  browser provides close code/reason, retain those details. Recoverable malformed
  frames show their error category without changing server authority; a newer
  applicable snapshot clears that diagnostic. Protocol injection is covered by
  automated doubles, not a required user-facing control.
- After disconnect/reload, verify the separate offline identity and the new
  assigned identity respectively. Observer/respawning/eliminated rows retain
  ownership markers but have no floating `Tú` while their bodies are absent.

The checklist is guidance, not evidence of a completed native-browser or
remote-device run. JVM socket checks run on the service host; Node doubles and
the release compiler cannot establish physical-device connectivity or layout.
