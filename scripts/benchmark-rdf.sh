#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 6 || $# -gt 8 ]]; then
  echo "Usage: $0 OLD_DIR OLD_MANIFEST_SHA OLD_COMMIT NEW_DIR NEW_MANIFEST_SHA NEW_COMMIT [ITERATIONS] [WORKERS]" >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
jar_file=${CURRICULA_BENCHMARK_JAR:-"$script_dir/../target/curricula-api-0.0.1-SNAPSHOT.jar"}
java=${JAVA_HOME:+"$JAVA_HOME/bin/"}java
javac=${JAVA_HOME:+"$JAVA_HOME/bin/"}javac
jar=${JAVA_HOME:+"$JAVA_HOME/bin/"}jar
iterations=${7:-80}
workers=${8:-4}
synthetic_concepts=${CURRICULA_BENCHMARK_SYNTHETIC_CONCEPTS:-0}
store_parent=${CURRICULA_BENCHMARK_STORE_PARENT:-${TMPDIR:-/tmp}}
if [[ ! -f "$jar_file" ]]; then
  echo "Build the branch with ./mvnw package or provide CURRICULA_BENCHMARK_JAR" >&2
  exit 2
fi
if [[ ! "$iterations" =~ ^[1-9][0-9]*$ || ! "$workers" =~ ^[1-9][0-9]*$ ]]; then
  echo "ITERATIONS and WORKERS must be positive integers" >&2
  exit 2
fi
if [[ ! "$synthetic_concepts" =~ ^(0|[2-9]|[1-9][0-9]+)$ ]]; then
  echo "CURRICULA_BENCHMARK_SYNTHETIC_CONCEPTS must be zero or at least two" >&2
  exit 2
fi
if [[ "$2" == "$5" ]]; then
  echo "Provide different manifest digests to exercise release activation" >&2
  exit 2
fi

jar_file=$(realpath -- "$jar_file")
scratch=$(mktemp -d "$store_parent/curricula-benchmark.XXXXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
(cd "$scratch" && "$jar" xf "$jar_file" BOOT-INF/classes BOOT-INF/lib)
classpath="$scratch:$scratch/BOOT-INF/classes:$scratch/BOOT-INF/lib/*"
"$javac" -cp "$classpath" -d "$scratch" "$script_dir/RdfBenchmark.java"

jfr_dir=""
if [[ -n "${CURRICULA_BENCHMARK_JFR_DIR:-}" ]]; then
  mkdir -p -- "$CURRICULA_BENCHMARK_JFR_DIR"
  jfr_dir=$(mktemp -d "$(realpath -- "$CURRICULA_BENCHMARK_JFR_DIR")/run.XXXXXXXX")
  printf '# JFR recordings: %s\n' "$jfr_dir"
fi

run() {
  local recording=()
  if [[ -n "$jfr_dir" ]]; then
    recording=("-Xlog:jfr+startup=warning" "-XX:StartFlightRecording=filename=$jfr_dir/$1.jfr,settings=profile")
  fi
  "$java" "-Xmx${CURRICULA_BENCHMARK_HEAP:-768m}" -XX:ActiveProcessorCount="$workers" \
    "${recording[@]}" -cp "$classpath" live.curricula.api.rdf.RdfBenchmark "$@"
}

"$java" -version 2>&1 | sed 's/^/# /'
printf '# Kernel architecture: %s\n' "$(uname -sm)"
printf '# Executed: %s\n' "$(date -u +%FT%TZ)"
printf '# Query samples per workload: %s, concurrent workers: %s, heap maximum: %s\n' \
  "$iterations" "$workers" "${CURRICULA_BENCHMARK_HEAP:-768m}"
printf '%s\n' "stage,operations,wall_ms,p50_ms,p95_ms,ops_per_second,allocated_bytes,process_peak_rss_kib"
run initial-import "$scratch/store" "$1" "$2" "$3"
run restart-old "$scratch/store" "$2" "$iterations" "$workers"
run import-and-activate "$scratch/store" "$4" "$5" "$6"
run restart-new "$scratch/store" "$5" "$iterations" "$workers"
run rollback-and-reactivate "$scratch/store" "$2" "$5" "$4" "$6"
if [[ "$synthetic_concepts" != 0 ]]; then
  run generate-synthetic-fixture "$scratch/synthetic" "$4" "$5" "$synthetic_concepts"
  synthetic_manifest=$(sha256sum -- "$scratch/synthetic/manifest.json" | cut -d' ' -f1)
  run import-synthetic "$scratch/store" "$scratch/synthetic" "$synthetic_manifest" \
    0000000000000000000000000000000000000000
  run restart-synthetic "$scratch/store" "$synthetic_manifest" "$iterations" "$workers"
fi
printf '# Store allocated bytes per release and total\n'
du -B1 -s -- "$scratch/store"/releases/*
du -B1 -s -- "$scratch/store"
printf '# Store apparent bytes per release and total\n'
du -B1 -s --apparent-size -- "$scratch/store"/releases/*
du -B1 -s --apparent-size -- "$scratch/store"
