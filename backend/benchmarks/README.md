# benchmarks

JMH micro-benchmarks for the read path, which the PDF requires to be answered "in microseconds".

`LocalReadBenchmark` builds a realistic snapshot (300 clients × 5 offices × 2 shifts, 208 settings,
about 35,000 stored values) and measures:
- resolving a value stored at SHIFT level, and one that falls through all four levels to the default;
- `client.view(ctx)` plus reading two related keys, single-threaded and with 4 threads;
- applying one pushed change to the snapshot.

Measured results (tens of nanoseconds per read) are in the main README §3 and in `docs/results/`.

```bash
cd backend
./mvnw -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar
```
