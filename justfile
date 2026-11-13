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
