# config-server

The source of truth: a Spring Boot service that validates, versions and stores every configuration
change in PostgreSQL, and pushes each committed change to connected clients.

## What it does
- **REST API** (`api`): change sets, definitions and constraints, resolve with explain mode, staged
  rollouts (stage / promote / abort), scoped rollback with a dry-run diff, audit history, sync
  endpoints for clients, fault injection for tests.
- **Single write path** (`service/ChangeSetService`): authorize → validate the whole change set →
  take a row lock and store it as the next gap-free version in one transaction → swap the in-memory
  snapshot → push. Nothing is pushed before it is durable; nothing invalid is ever stored.
- **Persistence** (`repository`, Flyway migrations in `src/main/resources/db/migration`): an append-only
  `change_set` table. On startup the state is rebuilt by replaying it.
- **Push and sync** (`service/SubscriptionHub`, `service/SyncService`): SSE fan-out with heartbeats; a
  delta "since version N" from the in-memory event window, or a cached full snapshot.
- **Security** (`security`): JWT access tokens, roles OPERATOR / ADMIN / EMPLOYEE / SERVICE, and tenant
  isolation enforced by `AccessPolicy` and `TenantFilter`. No credential or signing key is stored in the
  code; they come from the environment / `.env`.
- **Errors and monitoring** (`error`, `observability`): RFC 7807 problem responses without internals or
  personal data, and Prometheus metrics (version, rejections, subscribers, queue depth, push lag).

## Run
From the project root: `scripts/run-local.sh`, `docker compose up --build`, or IntelliJ (VM option
`-Dspring.profiles.active=local,demo` for H2, `demo` for PostgreSQL). See the main README.

## Tests
Integration tests start the real server against an embedded PostgreSQL: `ApiIT`, `TenantIsolationIT`,
`RolloutRollbackIT`, `ConcurrencyAndDurabilityIT`, `EndToEndIT` (push, missed push, kill-the-server,
atomic reads over the network), `CredentialConfigurationTest`; with `-Pscale` also `ScaleIT` (200 and 500
clients) and `LoadIT` (throughput and p50/p99).

```bash
cd backend
./mvnw -pl config-server -am verify
```
