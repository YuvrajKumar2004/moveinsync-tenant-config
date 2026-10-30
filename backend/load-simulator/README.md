# load-simulator

Tools that prove the scale requirements: fleets of real embedded clients, and an HTTP load generator.
Used by the automated scale tests and as a command-line tool against a running server.

## What it does
- `Fleet` starts N `config-client` instances in one JVM (each with its own SSE stream and poller) and
  records when every client saw every version.
- `Experiments` measures propagation (commit → visible in every client) and runs closed-loop HTTP load
  with throughput and p50 / p90 / p99 latency (`Stats`).
- `AdminApi` publishes changes as the operator; `SimulatorMain` is the command line.

## Command line
Load the passwords first (`set -a; . ./.env; set +a` from the project root), then:
```bash
java -jar backend/load-simulator/target/load-simulator-1.0.0-all.jar propagation --url http://localhost:8080 --clients 200 --rounds 10
java -jar backend/load-simulator/target/load-simulator-1.0.0-all.jar load        --url http://localhost:8080 --threads 32 --seconds 20
java -jar backend/load-simulator/target/load-simulator-1.0.0-all.jar watch       --url http://localhost:8080 --clients 50
```
`watch` keeps a fleet connected and prints its state every second: kill and restart the server while it
runs to see reads continue and the fleet catch up.

The automated proofs (`ScaleIT`, `LoadIT`) live in `config-server` and use this module.
