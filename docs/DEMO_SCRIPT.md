# Demo video script (about 8 minutes)

Setup before recording:
- Two terminals (A and B) in the repository root, plus Postman with
  `docs/postman/tenant-config.postman_collection.json` imported.
- Run `./mvnw -DskipTests install` once.
- With Docker, use `scripts/init-env.sh && docker compose up --build`. Without it, use `scripts/run-local.sh` as shown below (it creates the git-ignored `.env` with random passwords on first run).
- For Postman, copy the four `DEMO_*_PASSWORD` values from `.env` into the collection variables.

## 0. Intro (30 s)
Show `README.md` §4 and the architecture diagram in `docs/DESIGN.md`. Say it in one sentence: *one
writer with a versioned, validated change log; every service embeds a client that reads from memory
and is kept fresh by SSE push plus a version poll.*

## 1. Start everything (30 s)
Terminal A:
```bash
scripts/run-local.sh
```
Expected: `config-server up on :8080` and `booking-service up on :8081`.
(Docker variant: `scripts/init-env.sh && docker compose up --build`.)

## 2. Example use case: Bengaluru cut-off (1.5 min)
Terminal A:
```bash
scripts/demo.sh example
```
Point out each step:
1. The explain output says `booking.cutoff = PT1H`, source `DEFAULT`, level `GLOBAL`.
2. A booking 45 minutes ahead at blr-1 → **422 rejected** (cut-off 60 min).
3. The operator commits `OFFICE acme/blr-1 = 30m` → 201 with version, author, reason and diff.
4. "booking service saw version N after … ms" → the push arrived.
5. The same booking at blr-1 → **201 accepted**; at blr-2 → **422** (still 60).
6. Explain now shows level `OFFICE`, source `OVERRIDE`.

## 3. Validation and atomic change sets (1 min)
```bash
scripts/demo.sh validation
```
- `-30m` → 422 `INVALID_VALUE`.
- `06:00-22:00` for the escort window → 422 (longer than the 12 h maximum).
- Changing only `night.window.start` → 422 `CONSTRAINT_VIOLATION` (start and end are validated
  together).
- Changing both in one change set → 201.
- The version only moved for the valid change.

Mention the test that proves atomic reads: `EndToEndIT.continuousClientReadsNeverSeeAMixedNightWindow`
(about 1 M reads during 40 flips, 0 mixed).

## 4. Staged rollout, promote, rollback (1.5 min)
```bash
scripts/demo.sh rollout
scripts/demo.sh rollback
```
- The rollout targets blr-1 and hyd-1. Explain at blr-1 shows `source: STAGED, rolloutVersion`;
  blr-2 still shows the default.
- Promote → blr-2 now has the new value too.
- Rollback dry run shows the diff. Apply creates a **new** version of kind `ROLLBACK`, and the
  45-minute booking at blr-1 is rejected again.

## 5. Security (30 s)
```bash
scripts/demo.sh isolation
```
- acme-admin writing globex → 403.
- acme-employee reading globex → 403.
- acme-employee writing → 403.

## 6. Live failure: kill the config server (2 min)
Terminal B: watch a fleet of 50 embedded clients.
```bash
set -a; . ./.env; set +a
java -jar load-simulator/target/load-simulator-1.0.0-all.jar watch --url http://localhost:8080 --clients 50
```
Terminal A:
```bash
scripts/run-local.sh stop-server          # docker: docker compose kill config-server
curl -s -X POST localhost:8081/bookings -H 'Content-Type: application/json' \
  -d '{"clientId":"acme","officeId":"blr-1","minutesBeforeDeparture":45}'
curl -s localhost:8081/config/status
```
- Terminal B keeps printing values with `streams=0/50`, and staleness grows. **Reads never stop.**
- The booking still gets an answer, and `/config/status` shows `streamConnected:false`.

Restart the server, then commit a change:
```bash
scripts/run-local.sh start-server         # docker: docker compose start config-server
scripts/demo.sh faults
```
- Terminal B: streams climb back to 50/50, spread out by jitter, and the version catches up.
- `faults`: with every push dropped, the change still reaches the booking service ("healed without
  push after ~1.3 s"), and the Prometheus counters (operator token required; anonymous gets 401) show `config_faults_push_dropped_total`,
  `config_changes_rejected_total`, `config_stream_subscribers` and `config_push_lag_seconds`.

## 7. Proof (1 min)
Show `README.md` §3:
- JMH reads in tens of nanoseconds.
- 200 clients at p99 43 ms.
- The 500-client reconnect storm with 0 full snapshots.
- LoadIT at 13.5k req/s.

Show the commands that reproduce them:
```bash
./mvnw -Pscale -pl config-server test -Dtest='ScaleIT,LoadIT'
java -jar benchmarks/target/benchmarks.jar
```

## 8. Postman (30 s)
Run *Auth* → *Change sets* → *Safe changes* → *Isolation* to show the same calls in Postman.

Finish with `scripts/run-local.sh stop`.
