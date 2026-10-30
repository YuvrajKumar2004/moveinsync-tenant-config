# Requirements checklist

Status: ✅ implemented and verified by an automated test that was run · 🟡 implemented, verified
manually or only partly · ⛔ not verified (environment blocker).

All Java modules live under `backend/`; test paths below are relative to the module (for example `backend/config-server/src/test/java/...`).

## I. Model settings and levels

| # | Requirement (PDF) | Implementation | Verification | Status |
|---|---|---|---|---|
| 1 | Definitions: key, type (number, duration, time window, enum, list), validation rules, global default | `config-core/.../type/*Type.java` (`SettingType` hierarchy), `SettingDefinition` | `SettingTypesTest` (6), `ChangeSetValidatorTest.redefinitionMustKeepExistingOverridesValid` | ✅ |
| 2 | Overrides at CLIENT, OFFICE, SHIFT | `Scope`, `Snapshot` overrides map, `Op.SetOverride/RemoveOverride` | `ResolutionTest`, `ApiIT` | ✅ |
| 3 | Most-specific-wins | `Snapshot.resolve` | `ResolutionTest.mostSpecificLevelWinsAtEveryDepth`, `removingAnOverrideFallsBackToParent` | ✅ |
| 4 | Explain mode shows the supplying level | `Snapshot.explain`, `GET /resolve?explain=true`, `ConfigView.explain` | `ResolutionTest`, `ApiIT.bengaluruOfficeCutoff…IsExplained`, `RolloutRollbackIT` (STAGED source) | ✅ |
| 5 | Versioned changes: author, reason, monotonically increasing version | `change_set` table, `ChangeSetService.commit`, version row lock | `ApiIT.historyRecordsAuthorReasonAndIncreasingVersions`, `ConcurrencyAndDurabilityIT.concurrentWritersGetGapFreeVersions` (16 writers × 15, gap-free) | ✅ |

## II. The read path

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 6 | In-memory snapshot, microsecond reads, no network call | `ConfigClient.view` → `SnapshotHolder` (volatile read) | JMH `LocalReadBenchmark`: 12–53 ns avg (`docs/results/jmh-local-read.txt`) | ✅ |
| 7 | Change visible in every connected client within 5 s | SSE push (`SubscriptionHub`) | `ScaleIT.propagationTo200SimulatedClients…`: p99 43–78 ms, max 48–84 ms, 0 missed; `EndToEndIT.bengaluruExample…` | ✅ |
| 8 | Push (WebSocket/SSE/long-poll) + periodic version check that heals missed pushes | SSE + heartbeat + `pollLoop` (2 s ± 20%) | `EndToEndIT.droppedPushIsHealed…` (1.3–1.5 s), `ConfigClientTest.missedPushIsHealed…` | ✅ |
| 9 | Reconnect storm: 500 clients, jitter, only what changed since each client's version | `Backoff` (full jitter), `stream?since=`, in-memory event window, LRU snapshot cache, bounded snapshot builds + 503/Retry-After | `ScaleIT.reconnectStormOf500Clients…`: 0 full snapshots, 0 5xx, spread p50 2.2 s / p99 7.2 s; `ConfigClientTest.backoffUsesFullJitter…` | ✅ |

## III. Safe changes

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 10 | Atomic change sets; no client ever sees new start with old end | one event per change set; immutable snapshot swap; pinned `ConfigView`; `TIME_WINDOW_PAIR` constraint | `SnapshotHolderTest.continuousReadersNeverSeeAMixedNightWindow` (2,000 flips), `EndToEndIT.continuousClientReadsNeverSeeAMixedNightWindow` (over HTTP/SSE), `ChangeSetValidatorTest.nightWindowStartAndEndAreValidatedTogether` | ✅ |
| 11 | Staged rollout to two offices, promote later | `Op.Stage/Unstage`, `RolloutService`, `/rollouts` | `RolloutRollbackIT.stageToTwoOfficesThenPromoteToEveryone`, `adminCanStageAndAbort…`, `ResolutionTest.stagedValueAppliesOnlyToTargetOffices` | ✅ |
| 12 | Roll back to any previous version in one call, with a diff | `RollbackPlanner`, `RollbackService`, `POST /rollback` (dryRun) | `RolloutRollbackIT.rollbackOneClientToAnEarlierVersionWithDiff`, `RollbackAndWireTest` | ✅ |

## IV. Failure behaviour

| # | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| 13 | Server down: clients keep serving last good snapshot | read path never touches the network | `EndToEndIT.killTheServer…` (millions of reads during outage), `ConfigClientTest.keepsServingWhenTheServerDies…` | ✅ |
| 14 | Start up from a copy saved on disk | `SnapshotStore` (atomic rename, SHA-256) | `EndToEndIT.killTheServer…` (cold start while down), `ConfigClientTest.startsFromDiskWhenTheServerIsDown`, `corruptOrTamperedSnapshotFilesAreRejected` | ✅ |
| 15 | Bad values (−30 min, window ending before it starts) rejected before publish | `ChangeSetValidator`, 422 response, version unchanged | `ApiIT.invalidValuesAreRejectedAndNothingIsPublished`, `SettingTypesTest` | ✅ |
| 16 | Example: kill the server, reads continue, the change made just before the crash is applied everywhere after restart | durable commit before push; replay on startup; delta on reconnect | `EndToEndIT.killTheServer…` (push dropped, then kill, restart → 1.0–1.8 s), `ConcurrencyAndDurabilityIT.committedChangesSurviveRestart` | ✅ |
| 17 | Example: Bengaluru office 60 → 30 min; booking 45 min ahead accepted for that office only | `demo-booking-service` `BookingPolicy` | `BookingPolicyTest`, `EndToEndIT.bengaluruExample…`, live `scripts/demo.sh` run | ✅ |

## Key points

| # | Requirement | Where | Status |
|---|---|---|---|
| 18 | Consistency model in one paragraph | `docs/DESIGN.md` §3 | ✅ |
| 19 | What a client does with v41 after v43 (and after a server database reset) | `docs/DESIGN.md` §3 (history id); `ConfigClientTest.snapshotFromAWipedServerIsDiscarded…`, `runningClientNoticesTheServerHistoryChanged`, `EndToEndIT.snapshotFromAWipedDatabaseIsReplacedByTheNewServerState`; `SnapshotHolder`; `ConfigClientTest.duplicateAndOutOfOrder…`, `EndToEndIT.duplicateAndOutOfOrderPushes…` | ✅ |
| 20 | Test reading continuously during a change, never mixed | see #10 | ✅ |
| 21 | Benchmark local reads; propagation to 200 clients; kill-the-server test | see #6, #7, #16 | ✅ |

## Plus points

| # | Criterion | Implementation | Verification | Status |
|---|---|---|---|---|
| P1 | Authentication, roles (employee/admin/operator), tenant isolation | JWT (HS256), `SecurityConfig`, `AccessPolicy`, `TenantFilter` on snapshots/deltas/history | `TenantIsolationIT` (5), `ApiIT.authenticationIsRequired…`, `CredentialConfigurationTest` (no committed passwords/keys; users without a runtime password disabled; no start without a signing key), `EndToEndIT.serviceClientOnlyReceivesItsTenantsData` | ✅ |
| P2 | Time/space complexity + benchmark + scaling limits | README §5, JMH, LoadIT | measured | ✅ |
| P3 | Crashes, timeouts, duplicates, out-of-order, partial failures; failure-injection test | `FaultInjector`, idempotency keys, gap healing, handshake/idle timeouts, half-open detection | `EndToEndIT` (3 fault tests + kill), `ConfigClientTest` | ✅ |
| P4 | OOP, controller/service/repository layering | `SettingType` hierarchy, `Constraint` interface, `ConfigTransport` interface (HTTP + fakes), sealed `Op`; `api` → `service` → `repository` | code | ✅ |
| P5 | Trade-offs documented, what was left out | README §6, DESIGN §6 | ✅ |
| P6 | Metrics and structured logging (lag, error rate, rejections, retries, queue depth) | `ConfigMetrics`, client `ClientMetrics` → Micrometer, ECS JSON logs with requestId | `ApiIT.metricsExposeOnCallCountersToOperatorsOnly` (anonymous 401, non-operator 403) | ✅ |
| P7 | Caching with eviction; what is stale, for how long | client snapshot; server LRU of filtered snapshots; event window; DESIGN §5 | ScaleIT (0 full snapshots on reconnect) | ✅ |
| P8 | Non-leaking error messages, no PII in logs or errors | `GlobalExceptionHandler` (RFC 7807 + code + requestId), `PiiRedactor`, no body/query logging | `ApiIT.malformedRequestsGetGenericErrorsWithoutInternals`, `piiInReasonsIsNotEchoedInErrors`, `PiiRedactorTest` | ✅ |

## Advanced track and deliverables

| # | Requirement | Where | Status |
|---|---|---|---|
| A1 | Every hard rule has an automated test, incl. ≥1 concurrency and ≥1 failure test | this file; concurrency: `SnapshotHolderTest`, `EndToEndIT` atomic test, `ConcurrencyAndDurabilityIT`; failure: `EndToEndIT` | ✅ |
| A2 | Load test / benchmark with throughput, p50/p99, machine, in README | README §3, `LoadIT`, JMH | ✅ |
| A3 | Design note: architecture diagram, sequence diagram, failure-mode table | `docs/DESIGN.md` | ✅ |
| A4 | One-command run incl. database | `docker-compose.yml`, `Dockerfile` | ⛔ written but not executed: Docker is not installed on the build machine. No-Docker equivalent `scripts/run-local.sh` was run |
| D1 | README: how to run, assumptions, benchmark results, trade-offs | `README.md` | ✅ |
| D2 | Public GitHub repository with meaningful commit history | local git repo with incremental commits | 🟡 needs a manual push (no remote configured) |
| D3 | Demo video: main flows, one live failure, sample API calls | `docs/DEMO_SCRIPT.md` + `scripts/demo.sh` + Postman collection | 🟡 script ready; the video itself has to be recorded by a person |
