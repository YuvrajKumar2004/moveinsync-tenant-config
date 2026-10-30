# config-core

Plain-Java domain model shared by the server and the client library. No Spring and no runtime
dependencies, so all of the correctness rules can be tested without a web server or a database.

## What it does
- **Setting types and validation** (`type`, `validation`): NUMBER, DURATION, TIME, TIME_WINDOW, ENUM and
  LIST, each with its own rules (min/max, allowed values, crossing midnight…). Invalid values are
  rejected before anything is published.
- **Hierarchy and resolution** (`model`, `snapshot`): `Scope` (GLOBAL → CLIENT → OFFICE → SHIFT) and
  `ResolutionContext`. `Snapshot.resolve` returns the most specific value; `Snapshot.explain` also
  says which level supplied it (stored value, staged rollout or default).
- **Immutable snapshots** (`snapshot`): a change produces a new snapshot (copy-on-write).
  `SnapshotHolder` swaps it in one step and enforces "versions only move forward" (drops stale or
  duplicate events, detects gaps).
- **Atomic change sets** (`change`): `ChangeSetValidator` validates a whole change set against the
  resulting state, including cross-key rules such as the night-window start/end pair
  (`constraint/TimeWindowPairConstraint`).
- **Rollback and diffs** (`change`): `RollbackPlanner` and `DiffEntry` compute what a rollback to an
  earlier version changes.
- **Tenant filtering and wire format** (`snapshot/TenantFilter`, `wire`): what each tenant may see, and
  the JSON records exchanged with clients.

## Tests
`ResolutionTest`, `SettingTypesTest`, `ChangeSetValidatorTest`, `RollbackAndWireTest`, and
`SnapshotHolderTest` (which includes the concurrent "never a mixed night window" test).

```bash
cd backend
./mvnw -pl config-core test
```
