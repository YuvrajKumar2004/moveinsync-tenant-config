#!/usr/bin/env bash
# Walks through the case-study flows with curl. Works against `docker compose up` or scripts/run-local.sh.
# Needs only bash, curl, sed. Usage: scripts/demo.sh [all|example|validation|rollout|rollback|isolation|faults]
set -uo pipefail
CFG=${CFG:-http://localhost:8080}
BOOK=${BOOK:-http://localhost:8081}

step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
show() { printf '\033[0;33m$ %s\033[0m\n' "$*"; }

token() {
  curl -s -X POST "$CFG/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

OP=$(token operator REDACTED)
ADMIN=$(token acme-admin REDACTED)
EMP=$(token acme-employee REDACTED)
[[ -n "$OP" ]] || { echo "cannot log in to $CFG - is the server running?"; exit 1; }

api() { # method path [token] [body]
  local m=$1 p=$2 t=${3:-$OP} b=${4:-}
  show "curl -X $m $CFG$p ${b:+-d '$b'}"
  if [[ -n "$b" ]]; then
    curl -s -w '\n[HTTP %{http_code}]\n' -X "$m" "$CFG$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -w '\n[HTTP %{http_code}]\n' -X "$m" "$CFG$p" -H "Authorization: Bearer $t"
  fi
}

book() { # office minutesBeforeDeparture
  show "curl -X POST $BOOK/bookings -d '{clientId:acme, officeId:$1, minutesBeforeDeparture:$2}'"
  curl -s -w '\n[HTTP %{http_code}]\n' -X POST "$BOOK/bookings" -H 'Content-Type: application/json' \
    -d "{\"clientId\":\"acme\",\"officeId\":\"$1\",\"minutesBeforeDeparture\":$2}"
}

version() { curl -s "$CFG/api/v1/sync/version" -H "Authorization: Bearer $OP" | sed -n 's/.*"version":\([0-9]*\).*/\1/p'; }

example() {
  step "1. Example use case: Bengaluru office blr-1 cut-off 60 -> 30 minutes"
  api GET "/api/v1/resolve?clientId=acme&officeId=blr-1&key=booking.cutoff&explain=true"
  book blr-1 45
  api POST /api/v1/change-sets "$OP" '{"reason":"shorter cut-off for blr-1","ops":[{"op":"SET","level":"OFFICE","clientId":"acme","officeId":"blr-1","key":"booking.cutoff","value":"30m"}]}'
  local start; start=$(date +%s%N)
  local target; target=$(version)
  until [[ "$(curl -s "$BOOK/config/status" | sed -n 's/.*"version":\([0-9]*\).*/\1/p')" -ge "$target" ]]; do sleep 0.05; done
  echo "booking service saw version $target after $(( ($(date +%s%N) - start) / 1000000 )) ms (includes curl overhead)"
  book blr-1 45
  book blr-2 45
  api GET "/api/v1/resolve?clientId=acme&officeId=blr-1&key=booking.cutoff&explain=true"
}

validation() {
  step "2. Bad values are rejected before publishing"
  local before; before=$(version)
  api POST /api/v1/change-sets "$OP" '{"reason":"bad","ops":[{"op":"SET","level":"OFFICE","clientId":"acme","officeId":"blr-1","key":"booking.cutoff","value":"-30m"}]}'
  api POST /api/v1/change-sets "$OP" '{"reason":"bad","ops":[{"op":"SET","level":"OFFICE","clientId":"acme","officeId":"blr-1","key":"escort.window","value":"06:00-22:00"}]}'
  step "   Night window start and end are validated together (atomic change set)"
  api POST /api/v1/change-sets "$OP" '{"reason":"half change","ops":[{"op":"SET","level":"CLIENT","clientId":"acme","key":"night.window.start","value":"07:00"}]}'
  api POST /api/v1/change-sets "$OP" '{"reason":"move both ends","ops":[{"op":"SET","level":"CLIENT","clientId":"acme","key":"night.window.start","value":"22:00"},{"op":"SET","level":"CLIENT","clientId":"acme","key":"night.window.end","value":"05:00"}]}'
  echo "version before=$before after=$(version) (only the valid change set advanced it)"
}

rollout() {
  step "3. Staged rollout to two offices, then promote"
  local r; r=$(curl -s -X POST "$CFG/api/v1/rollouts" -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' \
    -d '{"reason":"pilot 100m max ride","targets":[{"clientId":"acme","officeId":"blr-1"},{"clientId":"acme","officeId":"hyd-1"}],"items":[{"op":"SET","level":"GLOBAL","key":"ride.max-duration","value":"100m"}]}')
  echo "$r"
  local rv; rv=$(echo "$r" | sed -n 's/.*"version":\([0-9]*\).*/\1/p')
  api GET "/api/v1/resolve?clientId=acme&officeId=blr-1&key=ride.max-duration&explain=true"
  api GET "/api/v1/resolve?clientId=acme&officeId=blr-2&key=ride.max-duration&explain=true"
  api POST "/api/v1/rollouts/$rv/promote" "$OP" '{"reason":"pilot went well"}'
  api GET "/api/v1/resolve?clientId=acme&officeId=blr-2&key=ride.max-duration&explain=true"
}

rollback() {
  step "4. Instant rollback of client acme to an earlier version (dry run, then apply)"
  local to=${ROLLBACK_TO:-1}
  api POST /api/v1/rollback "$OP" "{\"level\":\"CLIENT\",\"clientId\":\"acme\",\"toVersion\":$to,\"reason\":\"undo demo\",\"dryRun\":true}"
  api POST /api/v1/rollback "$OP" "{\"level\":\"CLIENT\",\"clientId\":\"acme\",\"toVersion\":$to,\"reason\":\"undo demo\"}"
  book blr-1 45
  api GET "/api/v1/change-sets?limit=3"
}

isolation() {
  step "5. Roles and tenant isolation"
  api POST /api/v1/change-sets "$ADMIN" '{"reason":"cross-tenant attempt","ops":[{"op":"SET","level":"OFFICE","clientId":"globex","officeId":"pune-1","key":"booking.cutoff","value":"5m"}]}'
  api GET "/api/v1/resolve?clientId=globex&key=booking.cutoff" "$EMP"
  api POST /api/v1/change-sets "$EMP" '{"reason":"employee write","ops":[{"op":"SET","level":"OFFICE","clientId":"acme","officeId":"blr-1","key":"booking.cutoff","value":"5m"}]}'
}

faults() {
  step "6. Failure injection: drop every push; the periodic check still delivers within 5 s"
  api PUT /api/v1/admin/faults "$OP" '{"dropPushes":true,"duplicatePushes":false,"outOfOrderPushes":false}'
  api POST /api/v1/change-sets "$OP" '{"reason":"while pushes are dropped","ops":[{"op":"SET","level":"OFFICE","clientId":"acme","officeId":"blr-1","key":"booking.cutoff","value":"25m"}]}'
  local start; start=$(date +%s%N); local target; target=$(version)
  until [[ "$(curl -s "$BOOK/config/status" | sed -n 's/.*"version":\([0-9]*\).*/\1/p')" -ge "$target" ]]; do sleep 0.1; done
  echo "healed without push after $(( ($(date +%s%N) - start) / 1000000 )) ms"
  curl -s "$BOOK/config/status"; echo
  api PUT /api/v1/admin/faults "$OP" '{"dropPushes":false,"duplicatePushes":false,"outOfOrderPushes":false}'
  step "   Metrics an on-call engineer would look at"
  curl -s "$CFG/actuator/prometheus" | grep -E '^config_(version_current|changes_rejected_total|stream_subscribers|stream_queue_depth|faults_push_dropped_total|push_lag_seconds\{quantile)' | head -12
}

case "${1:-all}" in
  all) example; validation; rollout; rollback; isolation; faults ;;
  *) "$1" ;;
esac
