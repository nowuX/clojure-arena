# Development

Install Java 21+, Clojure CLI, Node 22+, pnpm 12.17.0, and just.
The package manager and Shadow compiler version are pinned in package.json.

```sh
just setup
just dev
```

Open http://127.0.0.1:8081/ on the service host. For another device, obtain
its address with `tailscale ip -4`, then run `just dev <TAILSCALE_IP>` and
open `http://<TAILSCALE_IP>:8081/`. A check on the service host does not
establish another device's connectivity.

```sh
just build
just build '{:output-dir "/tmp/clojure-arena-sequential-release/js"}'
```

The second build leaves watched public/js assets intact. HTML loads main.js;
Shadow calls clojure-arena.client/init. Generated files are ignored by Git.
A release compilation verifies the compiler boundary; inspect the served page
in a native browser to verify startup and appearance.
