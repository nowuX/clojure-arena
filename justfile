# List available commands.
default:
    @just --list

# Install the pinned dependency graph.
setup:
    pnpm install --frozen-lockfile --store-dir .pnpm-store

# Compile on changes and serve browser assets.
dev host="127.0.0.1":
    CLOJURE_ARENA_HOST={{ quote(host) }} pnpm exec shadow-cljs watch app

# Compile a release, optionally with isolated output.
build config="{}":
    pnpm exec shadow-cljs release app --config-merge {{ quote(config) }}

# Run simulation and client-boundary regressions in Node.
test:
    pnpm exec shadow-cljs compile test
    node .shadow-cljs/test.js

# Serve existing browser assets with the Clojure HTTP server.
server host="127.0.0.1" port="8082":
    clojure -M:server {{ quote(host) }} {{ quote(port) }}

# Run shared simulation and JVM HTTP/server regressions.
test-server:
    clojure -M:test-server

# Verify JVM, Node and an isolated optimized browser build.
check build_dir="/tmp/clojure-arena-sequential-check/js":
    just test-server
    just test
    just build {{ quote('{:output-dir "' + build_dir + '"}') }}
