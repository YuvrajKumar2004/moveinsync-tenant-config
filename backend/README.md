# backend

Maven multi-module project (Java 21 target, Spring Boot 3.5) for the hierarchical tenant configuration
service. Each module has its own README.

| Module | Purpose |
|---|---|
| [config-core](config-core) | Plain-Java domain: types, validation, GLOBAL → CLIENT → OFFICE → SHIFT resolution, snapshots, change sets, rollback |
| [config-server](config-server) | Source of truth: REST API, PostgreSQL change log, SSE push, auth and tenant isolation |
| [config-client](config-client) | Library embedded by every service: in-memory reads, live updates, disk fallback |
| [demo-booking-service](demo-booking-service) | The PDF's booking cut-off example, built on the client library |
| [load-simulator](load-simulator) | Client fleets and HTTP load used to prove propagation and reconnect-storm behaviour |
| [benchmarks](benchmarks) | JMH benchmarks for local reads |

Also here: `Dockerfile` (builds both services; used by `docker-compose.yml` in the project root) and
the Maven wrapper (`mvnw`).

```bash
cd backend
./mvnw clean verify              # all modules, unit + integration tests
./mvnw -Pscale clean verify      # plus the 200/500-client and load tests
```
