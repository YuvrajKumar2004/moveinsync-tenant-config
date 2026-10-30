# config-client

The library every service embeds. Reads are answered from an in-memory snapshot with no network call;
changes arrive by push within milliseconds, and a periodic check guarantees they arrive within seconds.

## What it does
- **Local reads** (`ConfigClient`, `ConfigView`): `client.view(ctx)` pins one snapshot version, so
  related keys read in one request are always consistent. A read is a volatile read plus a few hash
  lookups (tens of nanoseconds).
- **Live updates** (`transport/HttpConfigTransport`): a server-sent-events stream pushes every
  committed change. Heartbeats and a version check every 2 s (±20 % jitter) catch any missed push and
  fetch only the missing changes.
- **Ordering**: duplicate or older events are dropped, a gap triggers a delta fetch, and a different
  server history (database reset) makes the client resync from the server.
- **Failures**: reconnects use exponential backoff with full jitter (`Backoff`). The last good
  snapshot is saved to disk with a checksum (`SnapshotStore`), so a service can start and keep serving
  while the server is down.
- **Metrics** (`ClientMetrics`): applied events, stale drops, gaps, heals, reconnects, staleness.

## Usage
```java
ConfigClient config = ConfigClient.builder()
        .server("http://config-server:8080", "booking-service", password)
        .snapshotFile(Path.of("data/config.snapshot.json"))
        .build().start();
Duration cutoff = config.view("acme", "blr-1", null).duration("booking.cutoff");
```

## Tests
`ConfigClientTest` runs the client against an in-memory `FakeServer` with switchable faults (dropped,
duplicate and out-of-order pushes, server down, corrupt disk file, history reset).

```bash
cd backend
./mvnw -pl config-core,config-client test
```
