# demo-booking-service

The PDF's example use case: a separate service that embeds `config-client` and enforces the booking
cut-off, so a configuration change visibly changes its behaviour without a restart.

## What it does
- `POST /bookings` with `{clientId, officeId, shiftId?, minutesBeforeDeparture}` returns **201 accepted**
  or **422 rejected**, together with the cut-off used, the level it came from and the config version
  (`BookingController`, `BookingPolicy`).
- `GET /config/status` shows the embedded client's snapshot version, stream connection and metrics.
- `ConfigClientConfiguration` builds the client (server URL and password from the environment or the
  root `.env`; snapshot file in `data/`) and exposes its counters as Micrometer metrics.

Example: with office `blr-1` at a 30-minute cut-off, a booking 45 minutes before departure is
accepted there, while office `blr-2` (default 60 minutes) still rejects it.

## Tests
`BookingPolicyTest` checks the accept/reject rule against a snapshot.

```bash
cd backend
./mvnw -pl demo-booking-service -am test
```
