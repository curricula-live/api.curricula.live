# RDF consumer benchmarks

Run the harness on Linux with a Java 25 JDK, Bash, GNU coreutils, a packaged API JAR, and two pinned RDF fixtures. The benchmark store and any synthetic data live in a disposable directory. Set `CURRICULA_BENCHMARK_STORE_PARENT` to a writable directory on the filesystem you want to benchmark.

```bash
# From the API checkout
bash scripts/fetch-integration-fixture.sh
./mvnw -B -ntp -DskipTests package

CURRICULA_BENCHMARK_SYNTHETIC_CONCEPTS=10000 scripts/benchmark-rdf.sh \
  target/ontology-fixture \
  4cf71b7d381034596432d4e373dfa8d4509412f98d700b43c47d13d493284b42 \
  abbef1de99953316bcd0663f50f3a88a20d38c11 \
  target/ontology-fixture-0.6.0 \
  ed70ec240ee39fc1b10026848b4cc4b561d50e8344323c1d69e71d8a5b337b54 \
  2da2f7676dd1a68fc8dc01427a5240882c7f951a 80 4
```

Set `CURRICULA_BENCHMARK_JAR` to compare a prebuilt revision and `CURRICULA_BENCHMARK_HEAP` to vary the default `768m` heap ceiling. Set `CURRICULA_BENCHMARK_JFR_DIR` to collect a Java Flight Recorder profile per JVM. Run profiling separately from timing comparisons.

```bash
jfr view allocation-by-site /path/to/run.XXXXXXXX/restart-new.jfr
jfr view hot-methods /path/to/run.XXXXXXXX/restart-new.jfr
```

The lifecycle measures initial import, import and activation of the next release, rollback, reactivation of a stored release, and TDB2 open in a new JVM. The query stages measure the transactional Java adapters directly, including search results, exact concept lookup, and one- and three-hop neighborhoods. The mixed workload uses concurrent platform threads. Requests are warmed up before timed sampling. Startup times cover opening and validating the existing store. JVM launch, Spring HTTP overhead and remote RDF download fall outside the measurements.

`allocated_bytes` is the JVM's cumulative heap allocation counter delta across threads in each stage. Divide by the number of operations for the mean allocation per operation. `process_peak_rss_kib` is Linux `VmHWM` for that JVM up to the measurement, including mapped and native memory. It can retain the peak of an earlier workload. The output reports both allocated and apparent bytes for the persisted release directories. Free space should also accommodate staging and copies during import.

The optional synthetic workload extends the verified source with test-only SKOS labels and a high-degree taxonomy hub. It regenerates matching N-Quads, TriG, Turtle, and checksums. The all-zero source stamp identifies a local benchmark fixture. It has no producer attestation or release approval.

## Measured run

Run on 20 November 2026 with Temurin 25.0.5.1, a four-vCPU quota, a 768 MiB maximum Java heap, and Linux x86_64 overlay storage. The Phase 2 run used API commit `2e40471331a966aba981c4a95e20fdbd45ca6edb`. The comparison baseline was `7afbfda5ce2b910b91377d670d855b4c86d81a1d`. Each query workload recorded 80 samples after 12 warmup requests. The mixed workload used four threads and 80 total requests. The timings reflect a shared host. ARM64 capacity measurements remain pending.

| Dataset | Concepts | Named quads |
| --- | ---: | ---: |
| Formal Sciences 0.4.0 | 2,590 | 23,262 |
| Formal Sciences 0.6.0 | 2,751 | 27,927 |
| Benchmark-only 0.6.0 plus 10,000 synthetic concepts | 12,751 | 57,926 |

### Profile and search change

A baseline JFR recording of the 0.6.0 query stage showed allocation pressure from `DirectByteBuffer.slice()` (14.77%), TDB2 `NodeId.createRaw` (9.85%), and Jena tuple creation (6.18%). Label search consumed about 68 MB of cumulative JVM allocations per request. Commit `c581c00` scans each searchable predicate's TDB2 index and resolves preferred labels only for matching concept resources, retaining the best bounded results. A regression test covers all five searchable fields, case and language, duplicate matches, deterministic result order, graph isolation, and the result limit.

### Lifecycle and storage

| Operation | Wall time | JVM allocated | Peak RSS |
| --- | ---: | ---: | ---: |
| Initial 0.4.0 import | 9.29 s | 839 MB | 471 MiB |
| Open 0.4.0 in a fresh JVM | 2.20 s | 59.9 MB | 123 MiB |
| Import and activate 0.6.0 | 6.30 s | 948 MB | 534 MiB |
| Open 0.6.0 in a fresh JVM | 2.73 s | 60.7 MB | 124 MiB |
| Roll back to 0.4.0 | 139 ms | 13.2 MB | 151 MiB |
| Re-activate stored 0.6.0 | 179 ms | 15.1 MB | 151 MiB |
| Import and activate expanded fixture | 10.74 s | 1,908 MB | 578 MiB |
| Open expanded fixture in a fresh JVM | 3.25 s | 75.5 MB | 146 MiB |

The three retained releases occupied 39.7 MiB on this filesystem and 578.8 MiB of apparent space. The real 0.6.0 TDB2 release occupied 9.6 MiB of blocks with the hardened store and 192.7 MiB with the baseline, because the atomic staging move preserves sparse files that the old copy expanded. Sparse file handling in backups and on the target filesystem affects capacity.

### Query latency

Each pair gives p50 and p95 in milliseconds. Throughput summarizes the four-thread mixed workload.

| Workload | 0.6.0 baseline | 0.4.0 Phase 2 | 0.6.0 Phase 2 | Expanded Phase 2 |
| --- | ---: | ---: | ---: | ---: |
| Label search | 56.86 / 89.77 | 16.84 / 40.08 | 6.99 / 23.35 | 25.83 / 93.07 |
| Exact concept | 0.023 / 0.087 | 0.045 / 0.140 | 0.060 / 0.495 | 0.059 / 0.280 |
| One hop | 0.111 / 0.371 | 0.338 / 2.906 | 0.553 / 1.656 | 0.316 / 0.788 |
| Three hops | 1.43 / 5.30 | 3.25 / 30.20 | 2.13 / 16.63 | 3.10 / 26.34 |
| Dense synthetic hub, one hop | N/A | N/A | N/A | 80.19 / 138.61 |
| Concurrent mixed | 0.86 / 212.58 | 2.11 / 35.46 | 1.86 / 26.49 | 1.18 / 83.15 |
| Mixed throughput, queries/s | 91 | 330 | 539 | 190 |

0.6.0 search allocation fell from 68.0 MB to 1.44 MB per request. The expanded dataset averaged 5.82 MB per search. Query-process peak RSS was 386 MiB on 0.6.0 and 465 MiB on the expanded dataset.

Phase 2 checks all matching neighbors for deterministic selection and returns canonical node metadata. That extra work increases hop latency compared with the baseline. At a synthetic hub with 9,999 incoming edges, bounded one-hop traversal still averaged 31.4 MB of cumulative allocations per request. Highly connected nodes remain a distinct workload from typical exploration.
