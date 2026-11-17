# syntax=docker/dockerfile:1

FROM node:22-trixie-slim AS bundle
RUN apt-get update \
 && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN npm install --global pnpm@12.17.0
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY shadow-cljs.edn ./
COPY src ./src
COPY public ./public
RUN pnpm exec shadow-cljs release app

FROM clojure:tools-deps AS runtime
WORKDIR /app
COPY deps.edn ./
RUN clojure -P -M:server
COPY src ./src
COPY public ./public
COPY --from=bundle /build/public/js ./public/js
EXPOSE 8082
CMD ["-M:server", "0.0.0.0", "8082"]
