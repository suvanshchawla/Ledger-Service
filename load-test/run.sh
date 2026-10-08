#!/usr/bin/env bash
# Runs one load scenario against a fresh stack and checks the ledger afterwards.
#
#   SCENARIO=ramp MAX_RATE=400 load-test/run.sh
#   SCENARIO=smoke load-test/run.sh        # the one-user smoke test instead of a load scenario
#
# Starts a throwaway PostgreSQL 16 container, builds and starts the app as a jar, samples the
# connection pool, runs k6, then runs verify.sql. Everything is stopped on exit. Exits non-zero if
# k6's thresholds fail or any ledger invariant is violated. Needs: docker, java, k6.
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO=${SCENARIO:-baseline}
PG_PORT=${PG_PORT:-5434}
APP_PORT=${APP_PORT:-8082}
PG_NAME=ledger-loadtest-pg
OUT=load-test/results
NAME="$(date +%Y%m%d-%H%M%S)-${SCENARIO}"
mkdir -p "$OUT"

command -v k6 >/dev/null || { echo "k6 not found on PATH" >&2; exit 1; }
APP_PID=""
SAMPLER_PID=""

cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null || true
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  docker stop "$PG_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== starting PostgreSQL on :$PG_PORT"
docker run -d --rm --name "$PG_NAME" -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger \
  -e POSTGRES_PASSWORD=ledger -p "$PG_PORT":5432 postgres:16 >/dev/null
until docker exec "$PG_NAME" pg_isready -U ledger -d ledger >/dev/null 2>&1; do sleep 1; done

echo "== building and starting the app on :$APP_PORT"
./gradlew bootJar -q --console=plain
JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)
java -jar "$JAR" --server.port="$APP_PORT" \
  --spring.datasource.url="jdbc:postgresql://localhost:$PG_PORT/ledger" \
  --management.endpoints.web.exposure.include=health,info,metrics \
  > "$OUT/$NAME-app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -sf "localhost:$APP_PORT/actuator/health" >/dev/null 2>&1 && break
  kill -0 "$APP_PID" 2>/dev/null || { echo "app exited early; see $OUT/$NAME-app.log" >&2; exit 1; }
  sleep 1
done

{
  echo "scenario: $SCENARIO"
  echo "date: $(date -Is)"
  echo "git: $(git rev-parse --short HEAD) $(git diff --quiet || echo '(uncommitted changes)')"
  echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs) ($(nproc) threads)"
  echo "ram_gb: $(free -g | awk 'NR==2{print $2}')"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "k6: $(k6 version | head -1)"
  echo "postgres: $(docker exec "$PG_NAME" postgres --version)"
  echo "k6 settings: ACCOUNTS=${ACCOUNTS:-200} RATE=${RATE:-20} MAX_RATE=${MAX_RATE:-400} DURATION=${DURATION:-30s} STAGE_DURATION=${STAGE_DURATION:-30s}"
  echo "note: k6, the app and PostgreSQL share this one machine; PostgreSQL and the app use default settings"
} > "$OUT/$NAME-env.txt"

# Once a second: epoch, active and pending connections in the app's pool, to see pool exhaustion.
metric() { curl -s "localhost:$APP_PORT/actuator/metrics/$1" | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2; }
( echo "epoch,pool_active,pool_pending"
  while true; do echo "$(date +%s),$(metric hikaricp.connections.active),$(metric hikaricp.connections.pending)"; sleep 1; done
) > "$OUT/$NAME-pool.csv" &
SAMPLER_PID=$!

SCRIPT=load-test/transfers.js
[ "$SCENARIO" = smoke ] && SCRIPT=load-test/smoke.js

echo "== k6: $SCENARIO"
K6_STATUS=0
k6 run -e BASE_URL="http://localhost:$APP_PORT" -e SCENARIO="$SCENARIO" \
  ${ACCOUNTS:+-e ACCOUNTS=$ACCOUNTS} ${RATE:+-e RATE=$RATE} ${MAX_RATE:+-e MAX_RATE=$MAX_RATE} \
  ${DURATION:+-e DURATION=$DURATION} ${STAGE_DURATION:+-e STAGE_DURATION=$STAGE_DURATION} \
  ${P99_MS:+-e P99_MS=$P99_MS} ${OPENING_BALANCE:+-e OPENING_BALANCE=$OPENING_BALANCE} \
  --summary-export "$OUT/$NAME-summary.json" "$SCRIPT" || K6_STATUS=$?

echo "== ledger checks (every number must be 0)"
docker exec -i "$PG_NAME" psql -U ledger -d ledger -v ON_ERROR_STOP=1 -tA -F ' | ' \
  < load-test/verify.sql | tee "$OUT/$NAME-verify.txt"
VIOLATIONS=$(awk -F' \\| ' '$2+0 > 0' "$OUT/$NAME-verify.txt" | wc -l)

echo "== results saved as $OUT/$NAME-*"
if [ "$VIOLATIONS" -gt 0 ]; then echo "FAILED: $VIOLATIONS ledger check(s) violated" >&2; exit 2; fi
if [ "$K6_STATUS" -ne 0 ]; then echo "FAILED: k6 exited with $K6_STATUS (thresholds or errors)" >&2; exit "$K6_STATUS"; fi
echo "OK: thresholds passed and the ledger is consistent"
