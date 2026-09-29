#!/usr/bin/env bash
# Starts a throwaway scala-pds and checks its output with the reference
# atproto TypeScript packages. Usage: bash scripts/interop.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
port="${PDS_INTEROP_PORT:-3199}"
work="$(mktemp -d)"
jar="$(ls "$root"/target/scala-*/scala-pds.jar 2>/dev/null | head -1 || true)"

cleanup() {
  [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

if [ -z "$jar" ]; then
  echo "interop: building the assembly"
  (cd "$root" && sbt -batch assembly >/dev/null)
  jar="$(ls "$root"/target/scala-*/scala-pds.jar | head -1)"
fi

if [ ! -d "$root/interop/node_modules" ]; then
  echo "interop: installing the reference packages"
  (cd "$root/interop" && npm install --silent)
fi

# A server left over from an earlier run would serve stale repositories.
if lsof -ti:"$port" >/dev/null 2>&1; then
  echo "interop: freeing port $port"
  lsof -ti:"$port" | xargs kill -9 2>/dev/null || true
  sleep 1
fi

echo "interop: starting scala-pds on port $port"
(cd "$work" && PDS_PORT="$port" PDS_ACCESS_LOG=false java -jar "$jar" > "$work/server.log" 2>&1) &
pid=$!

for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$port/xrpc/_health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done

if ! curl -fsS "http://127.0.0.1:$port/xrpc/_health" >/dev/null 2>&1; then
  echo "interop: the server did not start"
  cat "$work/server.log"
  exit 1
fi

status=0
for script in verify.mjs oauth.mjs; do
  set +e
  (cd "$root/interop" && PDS_URL="http://localhost:$port" node "$script")
  [ $? -ne 0 ] && status=1
  set -e
  echo
done

if [ "$status" -ne 0 ]; then
  echo
  echo "interop: server log"
  tail -40 "$work/server.log"
fi

exit "$status"
