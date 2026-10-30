#!/usr/bin/env bash
# Starts the config server (H2 file database, "local" profile) and the demo booking service from the
# built jars, without Docker. Logs and data go to ./data. Stop with: scripts/run-local.sh stop
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data

# secrets come from the git-ignored .env (random values, created on first run)
[[ -f .env ]] || scripts/init-env.sh
set -a; . ./.env; set +a
export CONFIG_SERVICE_PASSWORD="$DEMO_SERVICE_PASSWORD"

stop() {
  for name in config-server booking-service; do
    if [[ -f "data/$name.pid" ]]; then
      kill "$(cat "data/$name.pid")" 2>/dev/null || true
      rm -f "data/$name.pid"
      echo "stopped $name"
    fi
  done
}

wait_for() {
  local url=$1
  for _ in $(seq 1 60); do
    if curl -fsS "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "timed out waiting for $url" >&2
  return 1
}

case "${1:-start}" in
  stop) stop; exit 0 ;;
  start-server)
    java -jar config-server/target/config-server-1.0.0.jar --spring.profiles.active=local,demo > data/config-server.log 2>&1 &
    echo $! > data/config-server.pid
    wait_for http://localhost:8080/actuator/health && echo "config-server up on :8080 (log: data/config-server.log)"
    exit 0 ;;
  stop-server)
    kill "$(cat data/config-server.pid)" 2>/dev/null || true; rm -f data/config-server.pid; echo "config-server stopped"
    exit 0 ;;
esac

if [[ ! -f config-server/target/config-server-1.0.0.jar ]]; then
  ./mvnw -q -DskipTests install
fi
"$0" start-server
java -jar demo-booking-service/target/demo-booking-service-1.0.0.jar > data/booking-service.log 2>&1 &
echo $! > data/booking-service.pid
wait_for http://localhost:8081/actuator/health && echo "booking-service up on :8081 (log: data/booking-service.log)"
