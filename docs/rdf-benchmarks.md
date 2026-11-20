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
