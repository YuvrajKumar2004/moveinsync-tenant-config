# Hierarchical Tenant Configuration with Live Propagation

MoveInSync case study 2 (SDE Java backend, Advanced Track). It has two parts:
- A **configuration server**: settings defined once, overridden at GLOBAL → CLIENT → OFFICE → SHIFT,
  versioned, validated, rolled out in stages and rolled back.
- A **client library** that every service embeds. Reads come from an in-memory snapshot in
  nanoseconds, and changes arrive within milliseconds via server-sent events, with a periodic version
  check as the safety net.

- Design note (architecture, sequence diagram, consistency model, failure-mode table): [docs/DESIGN.md](docs/DESIGN.md)
- Requirement-by-requirement checklist with test evidence: [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md)
- Demo video script: [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md)
- Postman collection: [docs/postman/tenant-config.postman_collection.json](docs/postman/tenant-config.postman_collection.json)
- curl walkthrough: [scripts/demo.sh](scripts/demo.sh)
- Raw measurements: [docs/results/](docs/results/)

---

## 1. Run it

### One command (Docker)

```bash
scripts/init-env.sh && docker compose up --build
```

`init-env.sh` runs once to create the git-ignored `.env` with random secrets; after that,
`docker compose up --build` on its own is the one command. It starts PostgreSQL 16, `config-server` on **:8080** and `demo-booking-service` on **:8081**. On an
empty database the server commits the case-study setting catalog as version 1.

> **Not verified on the build machine:** Docker is not installed there, so the compose file and
> Dockerfile were written but never executed. Everything below was run.

### Without Docker

```bash
(cd backend && ./mvnw -DskipTests install)
scripts/run-local.sh            # server (H2 file DB, "local" profile) + booking service
scripts/demo.sh                 # every main flow with curl
scripts/run-local.sh stop
```

`scripts/run-local.sh stop-server` and `scripts/run-local.sh start-server` kill and restart only the
config server, for the failure demo. Requirements: JDK 21+ (built and tested on JDK 25.0.1) and
bash + curl (Git Bash on Windows).

### Without Docker, on a local PostgreSQL

`run-local.sh` uses a built-in H2 file database by default. To use a PostgreSQL server you
already have instead (tested with PostgreSQL 17 on Windows):

1. Create an empty database, e.g. `CREATE DATABASE config;` (psql or pgAdmin).
2. Add to the git-ignored `.env` (see the commented block in `.env.example`):
   ```
   CONFIG_PROFILES=demo
   DB_URL=jdbc:postgresql://localhost:5432/config
   DB_USER=postgres
   DB_PASSWORD=<your password>
   LOG_FORMAT=
   FAULTS_ENABLED=true
   ```
3. `scripts/run-local.sh`. The startup line names the database; Flyway creates the tables on first start.

In IntelliJ, use the VM option `-Dspring.profiles.active=demo` (instead of `local,demo`) for
`ConfigServerApplication`. The `DB_*` values are read from `.env`.

### From IntelliJ IDEA (one click)

1. Open the project folder in IntelliJ. If the Maven modules are not detected, right-click
   `backend/pom.xml` and choose **Add as Maven Project**.
2. Run `scripts/init-env.sh` once in a Git Bash terminal.
3. Create a **Compound** run configuration (call it *Whole application*). Put in it two
   **Application** configurations, both with working directory = the project root (not `backend/`):
   - `ConfigServerApplication` with VM option `-Dspring.profiles.active=local,demo`
   - `BookingApplication`

   Optionally add the npm script `dev` from `frontend/package.json` to the compound as well.
4. Run *Whole application*.

Both apps read their secrets from `.env` in the working directory
(`spring.config.import=optional:file:.env[.properties]`), so the run configurations need no
environment variables. Real environment variables still take precedence.

### Secrets and demo users

No password, password hash or signing key is committed. `scripts/init-env.sh` writes a git-ignored
`.env` with random values. Compose reads it automatically, and `scripts/run-local.sh` and
`scripts/demo.sh` load it (`run-local.sh` creates it on first use). [`.env.example`](.env.example)
lists the variables.

| user (demo profile) | password variable in `.env` | role | tenant |
|---|---|---|---|
| `operator` | `DEMO_OPERATOR_PASSWORD` | OPERATOR | all |
| `acme-admin` / `globex-admin` | `DEMO_ADMIN_PASSWORD` | ADMIN | acme / globex |
| `acme-employee` | `DEMO_EMPLOYEE_PASSWORD` | EMPLOYEE | acme |
| `booking-service` | `DEMO_SERVICE_PASSWORD` | SERVICE | `*` |
| `acme-service` | `DEMO_SERVICE_PASSWORD` | SERVICE | acme |

- A user whose password is missing or shorter than 12 characters is **disabled**.
- The server refuses to start without `JWT_SECRET` (≥ 32 bytes).
- Compose refuses to start if any value is missing.
- Read your passwords with `grep DEMO_ .env`.

---

## 2. Build and test

| What | Command | Runtime here |
|---|---|---|
| Build everything | `cd backend && ./mvnw -DskipTests install` | ~40 s |
| Unit + integration tests (70) | `cd backend && ./mvnw clean verify` | ~1.5 min |
| + scale and load tests (73) | `cd backend && ./mvnw -Pscale clean verify` | ~3 min |
| Only the scale proofs | `cd backend && ./mvnw -Pscale -pl config-server test -Dtest='ScaleIT,LoadIT'` | ~1.5 min (after one `install`) |
| JMH read benchmark | `java -jar backend/benchmarks/target/benchmarks.jar` | ~4 min |
| Load `.env` for the simulator commands below | `set -a; . ./.env; set +a` | |
| Propagation against a running server | `java -jar backend/load-simulator/target/load-simulator-1.0.0-all.jar propagation --url http://localhost:8080 --clients 200 --rounds 10` | |
| HTTP load against a running server | `java -jar backend/load-simulator/target/load-simulator-1.0.0-all.jar load --url http://localhost:8080 --threads 32 --seconds 20` | |
| Watch a fleet through a server kill | `java -jar backend/load-simulator/target/load-simulator-1.0.0-all.jar watch --url http://localhost:8080 --clients 50` | |

Integration tests start the real server (Tomcat, Spring Security, Flyway) against a **real PostgreSQL**
(zonky embedded binaries, PostgreSQL 14.22), so no Docker is needed. `-Dtest.db=h2` switches them to
H2. Each test can kill the server and start it again on the same port and database.

---

## 3. Measured results

Machine: **HP Victus 15 laptop, AMD Ryzen 5 5600H (6 cores / 12 threads, 3.3 GHz), 15.3 GB RAM,
Windows 11 (build 26300), JDK 25.0.1.** Client and server ran on the same machine. Raw output is in
[docs/results/](docs/results/).

### Local reads (JMH, 1 fork, 5×2 s; snapshot with 34,800 overrides, 208 settings)

| Benchmark | avg | p50 | p99 | p99.9 |
|---|---|---|---|---|
| `resolve` key overridden at SHIFT level | 41 ns | ≤100 ns | ≤100 ns | 400 ns |
| `resolve` falls through all 4 levels to the default | 12 ns | ≤100 ns | ≤100 ns | 200 ns |
| `client.view(ctx)` + read two related keys | 34 ns | ≤100 ns | ≤100 ns | 300 ns |
| same, 4 threads concurrently | 53 ns | ≤100 ns | ≤100 ns | 200 ns |
| apply one pushed change to the snapshot | 178 µs | | | |

The PDF asks for reads "in microseconds"; they take tens of **nanoseconds**. (Windows timer resolution
is 100 ns, so sample-mode percentiles show as 0 or 100 ns.)

### Propagation and failure tests (all automated)

Ranges cover the two full runs recorded in `docs/results/measurements.txt`.

| Scenario | Result |
|---|---|
| **200 simulated clients**, 20 changes (ScaleIT) | p50 **12–20 ms**, p99 **43–78 ms**, max **48–84 ms**; 0 missed in both runs |
| 200 clients against a separate server process (CLI, H2) | p50 17 ms, p99 120 ms, max 123 ms; 0 missed |
| Push deliberately dropped (fault injector) | healed in 1.3–1.5 s by heartbeat / version poll |
| 20 changes with every push duplicated and an older one re-sent | 20 stale messages dropped, never went backwards, final value correct |
| 4 reader threads during 40 night-window flips over the network | ~0.5–1.5 M reads, **0 mixed states** |
| **Kill the server**: change committed but not pushed, then the server is killed | 1.6–2.6 M reads served during a 2 s outage; a new instance cold-started from disk; every client had the pre-crash change 1.0–1.8 s after the server started again |
| **Reconnect storm**: 500 connected clients, server down 3 s, restarted | all 500 caught up 8.8–9.3 s after `start()` (includes Spring Boot startup); reconnects spread p50 2.2–3.2 s / p99 7.2–8.0 s by jitter; only delta answers, **0 full snapshots**, 0 handshake rejections, 0 server 5xx |

### HTTP load (LoadIT, PostgreSQL, closed loop, same machine)

Three runs on the same laptop, run straight after full build and test cycles. The spread shows how
much a shared, thermally limited machine varies; the request path itself did not change between runs.

| Endpoint | Threads | Throughput | p50 | p99 | Errors |
|---|---|---|---|---|---|
| `GET /api/v1/resolve?…&explain=true` | 32 | **4,025 – 13,534 req/s** | 1.85 – 6.87 ms | 9.6 – 32.4 ms | 0 |
| `POST /api/v1/change-sets` (validate + row lock + insert + push) | 8 | **508 – 1,344 commits/s** | 5.8 – 15.7 ms | 7.7 – 19.3 ms | 0 |

---

## 4. How it works (short version; details in [DESIGN.md](docs/DESIGN.md))

```
backend/                   Maven multi-module project (Java 21 target, Spring Boot 3.5)
  config-core              plain Java: types, validation, resolver, snapshots, version guard, diff, rollback
  config-server            Spring Boot: controller → service → repository; JWT; SSE hub; PostgreSQL change log
  config-client            embedded library: AtomicReference<Snapshot>, SSE + poll, disk copy, backoff
  demo-booking-service     the PDF's booking cut-off example as a consumer
  load-simulator           client fleets, propagation and HTTP load experiments, CLI
  benchmarks               JMH
  Dockerfile, mvnw         build files
frontend/                  React + Vite + Tailwind console (see §9)
docs/                      design note, requirements checklist, demo script, Postman, results
scripts/                   init-env.sh, run-local.sh, demo.sh
docker-compose.yml         PostgreSQL + config server + booking service (builds from backend/)
.env                       local secrets, git-ignored (created by scripts/init-env.sh)
```

- **Model.** Six types: NUMBER, DURATION, TIME, TIME_WINDOW, ENUM, LIST. Each is a subclass of
  `SettingType` with its own rules. Overrides are keyed by (key, scope).
  `Snapshot.resolve(ctx, key)` walks SHIFT → OFFICE → CLIENT → GLOBAL; `explain` returns the level,
  scope and source (OVERRIDE / STAGED / DEFAULT).
- **Write path.** Every change goes through `ChangeSetService.commit`:
  1. Authorize, tenant-aware.
  2. Validate the *whole* set against the resulting state, including cross-key constraints evaluated
     at every scope the change can affect.
  3. Take the version row lock and append the change set as version N+1 in one transaction.
  4. Swap the in-memory snapshot.
  5. Push.
- **Read path.** `client.view(ctx)` costs one volatile read. The view stays pinned to one version, so
  related keys read through it are always consistent with each other.
- **Freshness.** SSE push; a heartbeat with the server version every 5 s; a client version poll every
  2 s ± 20%. A gap triggers a delta fetch; anything at or below the current version is dropped.
- **Reconnect storm.** Full-jitter exponential backoff (250 ms → 10 s). Streams resume with `since=<version>`.
  The server keeps the last 10k events in memory, so a reconnect gets a few events instead of a
  snapshot. Full snapshots go through an LRU cache plus a semaphore; overflow gets 503 + Retry-After,
  which the client honours.

### API

| Method & path | Who | Purpose |
|---|---|---|
| `POST /api/v1/auth/token` | anyone | username/password → JWT (role, tenant claims) |
| `GET /api/v1/definitions` | authenticated | settings + constraints |
| `POST /api/v1/definitions`, `POST /api/v1/constraints` | OPERATOR | define / redefine (versioned) |
| `POST /api/v1/change-sets` (`Idempotency-Key`, `expectedVersion`) | OPERATOR, ADMIN (own tenant) | atomic change set |
| `GET /api/v1/change-sets[/{version}]` | OPERATOR, ADMIN (filtered) | audit history: author, reason, version, ops, diff |
| `GET /api/v1/resolve?clientId&officeId&shiftId&key&explain=true` | authenticated, own tenant | resolved values + source level |
| `GET/POST /api/v1/rollouts`, `POST /api/v1/rollouts/{v}/promote` / `abort` | OPERATOR, ADMIN (own tenant) | staged rollout |
| `POST /api/v1/rollback` `{level: GLOBAL\|CLIENT, clientId, toVersion, dryRun}` | OPERATOR; ADMIN for own client | scoped rollback with diff |
| `GET /api/v1/sync/version`, `/delta?since=N`, `/snapshot`, `/stream` (SSE) | SERVICE, OPERATOR | client library protocol (tenant-filtered) |
| `GET/PUT /api/v1/admin/faults` | OPERATOR, only if `FAULTS_ENABLED` | failure injection |
| `/actuator/health` (+ `/liveness`, `/readiness`) | public | probes |
| `/actuator/prometheus`, `/actuator/metrics`, `/actuator/info` | OPERATOR (bearer token) | metrics |

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"operator\",\"password\":\"$DEMO_OPERATOR_PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s -X POST localhost:8080/api/v1/change-sets -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"shorter cut-off","ops":[{"op":"SET","level":"OFFICE","clientId":"acme","officeId":"blr-1","key":"booking.cutoff","value":"30m"}]}'
curl -s "localhost:8080/api/v1/resolve?clientId=acme&officeId=blr-1&key=booking.cutoff&explain=true" -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8081/bookings -H 'Content-Type: application/json' -d '{"clientId":"acme","officeId":"blr-1","minutesBeforeDeparture":45}'
```

### Embedding the client

```java
ConfigClient config = ConfigClient.builder()
        .server("http://config-server:8080", "booking-service", System.getenv("CONFIG_SERVICE_PASSWORD"))
        .snapshotFile(Path.of("/app/data/config.snapshot.json"))
        .build().start();                       // works with the server down if the file exists

ConfigView v = config.view("acme", "blr-1", "night");   // pin one version per request
Duration cutoff = v.duration("booking.cutoff");
LocalTime start = v.time("night.window.start"), end = v.time("night.window.end"); // always consistent
```

---

## 5. Complexity and where it stops scaling

Let K = settings, O = overrides, o_k = overrides of one key, E = events retained, N = clients.

| Operation | Time | Space |
|---|---|---|
| Local read (`resolve`) | O(1): ≤ 4 levels × 2 hash lookups, no allocation | snapshot O(K + O) per client |
| Apply one pushed change | O(K + o_k) (copy-on-write of the top map and the touched key's map) | one extra snapshot version until GC |
| Validate a change set of m ops | O(m) + O(c × p) for c touched constraints evaluated at p scopes | O(K + o_k) candidate |
| Commit | validation + 1 row-locked transaction (1 INSERT, 1 UPDATE) | O(m) row |
| Push fan-out | O(N) sends per commit, on virtual threads | O(N) open streams |
| Delta for a reconnecting client | O(events missed), from memory | E events in memory |
| Server startup | O(total ops in the log) replay | |
| Rollback | O(V) replay to rebuild version V, plus O(O_tenant) diff | |

**Where it stops scaling, and why:**
1. **Write throughput.** 0.5–1.3k commits/s here. There is a single writer and a single row lock by
   design (total order, gap-free versions). Configuration changes are human-driven, so this has large
   headroom.
2. **Fan-out per server instance.** Each client holds one SSE connection. Tens of thousands of
   streams per instance are realistic with virtual threads; beyond that you need several read
   replicas fed by `LISTEN/NOTIFY` or a log (left out).
3. **Snapshot size per client.** Every client holds its tenants' whole configuration (~35k overrides
   ≈ a few MB in this benchmark). Hundreds of clients × hundreds of settings fits easily; millions of
   overrides per process would call for per-tenant partitioning of the subscription.
4. **Hot-key apply cost.** The key with the most overrides (here 4,800 for `booking.cutoff`) dominates
   the 178 µs apply. That's fine at human change rates, but a persistent map (HAMT) would make it
   O(log n).
5. **Startup replay** grows with history; periodic checkpoints would bound it (left out).

---

## 6. Trade-offs, assumptions and what was left out

**Trade-offs**
- *Consistency vs availability vs latency.* Reads are local and always available (AP), with bounded
  staleness while connected and measured staleness while disconnected. Writes are CP: a single writer
  and the database decide, and a write fails rather than forking history.
- *Push + poll.* Push gives millisecond latency; the 2 s poll bounds the worst case below 5 s even when
  every push is lost. That costs about N/2 cheap requests per second (≈250 req/s for 500 clients).
- *Event log as the source of truth.* Audit history, rollback to any version and crash recovery fall
  out of it; the cost is replay at startup.

**Assumptions (the PDF is silent on these)**
1. **Night window.** It can be one TIME_WINDOW value (`escort.window`, may cross midnight) or two TIME
   keys (`night.window.start` / `night.window.end`) tied by a `TIME_WINDOW_PAIR` constraint. The pair
   is validated together at every scope; start = end, or a window longer than its max, is rejected. A
   window "that ends before it starts" is rejected unless crossing midnight is explicitly allowed.
2. **Rollback is scoped**: GLOBAL values, or everything belonging to one client. It is applied as a new
   version with a diff, and it aborts active rollouts in that scope. Definitions are not rolled back.
3. **Roles**:
   - OPERATOR: everything.
   - ADMIN: own client's CLIENT/OFFICE/SHIFT values, rollouts and rollbacks.
   - EMPLOYEE: read resolved values for own client.
   - SERVICE: sync endpoints for its configured tenants.
   - Cross-tenant attempts get 403 with a generic message.
4. **Staged rollout**: a change set with target offices (`clientId/officeId`). Target offices see the
   staged value at the same precedence as a stored value at that scope; promote applies it to everyone
   atomically; abort removes it. While a rollout is active, plain changes to the same key and scope are
   refused.
5. **Hierarchy.** SHIFT ⊂ OFFICE ⊂ CLIENT. Ids are opaque; office ids are unique within a client.
   There is no org master-data service.
6. GLOBAL values are the definition default plus an optional operator-set GLOBAL override.
7. Durations are never negative. LIST items are tokens `[A-Za-z0-9_.-]`.

**Left out deliberately**
- Multi-instance server / HA writes.
- Log checkpoints.
- Deleting definitions.
- A real identity provider (users come from config).
- Login rate limiting.
- A UI.
- Per-key ACLs.

## 7. Limitations and manual steps

- **Docker path not executed** (no Docker on the build machine). To verify:
  1. `docker compose up --build`
  2. `scripts/demo.sh`
  3. Kill test: `docker compose kill config-server && sleep 5 && docker compose start config-server`
- Integration tests ran on embedded PostgreSQL **14.22**; compose uses **16**. The SQL is plain
  (no 16-only features).
- All measurements ran on one laptop, with client and server competing for the same CPU.
- Metrics need an OPERATOR bearer token, and tokens expire after 1 h. A production Prometheus would
  get a dedicated long-lived scrape credential, or a management port reachable only from the
  monitoring network. That is not implemented.
- The booking demo service has no authentication of its own, so its actuator exposes only `health`
  (opt in with `MANAGEMENT_EXPOSURE=health,prometheus`).
- The git history was rewritten to scrub the demo passwords, bcrypt hashes and fallback keys that early
  commits contained (they now read `REDACTED`). So commits before the security commit cannot log in as
  demo users; check out the latest commit to run the project.

## 8. Monitoring (what to look at at 3 AM)

**Server** (`/actuator/prometheus`, OPERATOR token):
- `config_version_current`
- `config_changes_committed_total{kind}` and `config_changes_rejected_total{reason=validation|conflict|forbidden}`
- `config_errors_total`
- `config_stream_subscribers` and `config_stream_queue_depth`
- `config_stream_events_sent_total{result}` and `config_push_lag_seconds{quantile}`
- `config_sync_delta_total{type=events|full|empty}`
- `config_stream_handshake_rejected_total`
- `config_faults_push_dropped_total`
- `http_server_requests_seconds` (error rate, latency)

**Client** (booking service):
- `config_client_version` and `config_client_stream_connected`
- `config_client_staleness_ms` and `config_client_last_lag_ms`
- `config_client_stale_dropped`, `config_client_gaps_detected`, `config_client_server_behind`, `config_client_history_resets` (server database replaced)
- `config_client_poll_heals`, `config_client_heartbeat_heals`
- `config_client_reconnect_attempts`, `config_client_sync_errors`, `config_client_disk_failures`

Logs are structured (ECS JSON by default; `LOG_FORMAT=` gives plain text) and carry a `requestId`.
Request bodies, query strings and headers are never logged, and free text (change reasons) passes
through a PII redactor (emails, phone numbers) before any log line or error response.

## 9. Frontend console (`frontend/`)

React 19 + Vite 7 + Tailwind 4 dashboard:
- dashboard;
- GLOBAL → CLIENT → OFFICE → SHIFT explorer (effective value, where it comes from, inheritance chain);
- editing, including atomic multi-key change sets;
- history with diffs and scoped rollback;
- staged rollouts (stage, promote, abort);
- settings catalog;
- live updates;
- booking-service status and a "try a booking" form;
- operator metrics and fault switches.

### Run on Windows (Git Bash)

1. Start the backend: `scripts/run-local.sh` (or **Whole application** in IntelliJ). The config server runs on :8080, the booking service on :8081.
2. Start the frontend:
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
3. Open http://localhost:5173 and sign in. The passwords are the `DEMO_*_PASSWORD` values in the root `.env`.
4. Production build:
   ```bash
   npm run build
   ```
5. Serve the build:
   ```bash
   npm run preview
   ```
   It is served at http://localhost:4173 with the same proxy.
6. Unit tests:
   ```bash
   npm test
   ```

Proxy targets come from `frontend/.env.local`; see `frontend/.env.example`, which contains placeholders only.

### Backend limitations that shape the UI (no backend code was changed)

- **No CORS on the config server.** The browser reaches it through the Vite dev/preview proxy (`/api`, `/actuator`, and `/booking-api` → :8081). Hosting the built files elsewhere needs a reverse proxy, or CORS on the server.
- **The SSE stream accepts only `Authorization: Bearer` and only the SERVICE or OPERATOR role.**
  - Operators get real SSE through a streamed `fetch()` with the header. `EventSource` can't send headers, and putting the token in the URL would leak it.
  - Admins and employees get a 403 from the stream, so the UI polls `/api/v1/resolve` (`snapshotVersion`) every 5 s instead.
- **No endpoint lists clients, offices or shifts.**
  - The explorer lists scopes that hold values (from `/sync/snapshot` for operators, or from tenant history for admins).
  - Any scope ID can also be typed in.
- **Tokens and the booking service.**
  - The access token is kept in `sessionStorage` for the tab only. No credentials are bundled with the app.
  - The demo booking service has no authentication of its own.
