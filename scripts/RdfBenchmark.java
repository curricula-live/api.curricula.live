package live.curricula.api.rdf;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import tools.jackson.databind.json.JsonMapper;

/** Standalone timing harness. Each invocation opens a private, disposable RDF store. */
public final class RdfBenchmark {
    private static final ThreadMXBean ALLOCATIONS =
            ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
    private static final List<String> SEARCH_TERMS = List.of("DPLL", "matrix", "algebra", "graph");
    private static final List<String> CONCEPTS =
            List.of("dpll-algorithm", "boolean-satisfiability", "unit-propagation");
    private static final String SYNTHETIC_BASE = RdfReleaseStore.BASE + "concept/benchmark-only-";

    private RdfBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (!ALLOCATIONS.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation measurements are unavailable on this JVM");
        }
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        String stage = args[0];
        Path root = Path.of(args[1]);
        switch (stage) {
            case "initial-import", "import-and-activate", "import-synthetic" -> {
                try (var store = new RdfReleaseStore(root)) {
                    require(store.available() != stage.equals("initial-import"), stage + " store state");
                    RdfReleaseStore.Status publication = timed(stage,
                            () -> store.ingest(Path.of(args[2]), args[3], args[4]));
                    verify(publication, args[3]);
                    describe(publication);
                }
            }
            case "restart-old", "restart-new", "restart-synthetic" -> {
                try (var store = timed(stage, () -> new RdfReleaseStore(root))) {
                    verify(store.status(), args[2]);
                    queries(stage, store, Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                }
            }
            case "rollback-and-reactivate" -> {
                try (var store = new RdfReleaseStore(root)) {
                    verify(store.status(), args[3]);
                    verify(timed("rollback", store::rollback), args[2]);
                    verify(timed("reactivate-stored-release",
                            () -> store.ingest(Path.of(args[4]), args[3], args[5])), args[3]);
                }
            }
            case "generate-synthetic-fixture" ->
                    generateSynthetic(root, Path.of(args[2]), args[3], Integer.parseInt(args[4]));
            default -> throw new IllegalArgumentException("Unknown stage: " + stage);
        }
    }

    private static void queries(String stage, RdfReleaseStore store, int iterations, int workers)
            throws Exception {
        var catalog = new JenaKnowledgeCatalog(store);
        var explorer = new RdfKnowledgeExplorer(store);
        IntConsumer search = i -> require(
                !catalog.search(SEARCH_TERMS.get(i % SEARCH_TERMS.size()), 25).isEmpty(), "search results");
        IntConsumer concept = i -> require(
                catalog.findBySlug(CONCEPTS.get(i % CONCEPTS.size())).isPresent(), "concept result");
        IntConsumer oneHop = i -> neighborhood(explorer, i, 1);
        IntConsumer threeHop = i -> neighborhood(explorer, i, 3);
        List<IntConsumer> operations = List.of(search, concept, oneHop, threeHop);

        for (var operation : operations) {
            for (int i = 0; i < 12; i++) operation.accept(i);
        }
        samples(stage + "-search", iterations, search);
        samples(stage + "-concept", iterations, concept);
        samples(stage + "-one-hop", iterations, oneHop);
        samples(stage + "-three-hop", iterations, threeHop);
        if (stage.equals("restart-synthetic")) {
            IntConsumer denseHub = i -> require(
                    !explorer.neighborhood(SYNTHETIC_BASE + "0", 1).edges().isEmpty(), "synthetic hub edges");
            denseHub.accept(0);
            samples(stage + "-dense-hub", iterations, denseHub);
        }

        var startGate = new CountDownLatch(1);
        var ready = new CountDownLatch(workers);
        var futures = new ArrayList<Future<long[]>>();
        try (var pool = Executors.newFixedThreadPool(workers)) {
            try {
                for (int worker = 0; worker < workers; worker++) {
                    int offset = worker;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        startGate.await();
                        int count = iterations / workers + (offset < iterations % workers ? 1 : 0);
                        return latencies(count,
                                i -> operations.get((i + offset) % operations.size()).accept(i + offset));
                    }));
                }
                ready.await();
                long allocated = allocatedBytes();
                long started = System.nanoTime();
                startGate.countDown();
                long[] results = new long[iterations];
                int offset = 0;
                for (var future : futures) {
                    long[] batch = future.get();
                    System.arraycopy(batch, 0, results, offset, batch.length);
                    offset += batch.length;
                }
                emit(stage + "-concurrent-mixed", results, System.nanoTime() - started, allocatedBytes() - allocated);
            } finally {
                startGate.countDown();
            }
        }
    }

    private static void generateSynthetic(Path target, Path source, String digest, int concepts)
            throws IOException {
        require(concepts >= 2, "at least two synthetic concepts to provide a dense-hub workload");
        var publication = RdfReleaseVerifier.verify(source, digest);
        Files.createDirectory(target);
        var json = JsonMapper.builder().build();
        var manifest = json.createObjectNode();
        manifest.put("contract", RdfReleaseVerifier.CONTRACT);
        manifest.put("namespace", publication.namespace());
        manifest.put("version", "benchmark-only-" + publication.version() + "-" + concepts);
        manifest.put("concepts", Math.addExact(publication.concepts(), concepts));
        manifest.put("conforms", true);
        var counts = manifest.putObject("counts");
        var artifacts = manifest.putObject("artifacts");

        var dataset = DatasetFactory.createTxnMem();
        try {
            dataset.begin(ReadWrite.WRITE);
            try {
                try (var input = Files.newInputStream(source.resolve("formal-sciences.nq"))) {
                    RDFDataMgr.read(dataset, input, Lang.NQUADS);
                }
                Model asserted = dataset.getNamedModel(publication.namespace() + "graph/asserted");
                var root = asserted.createResource(SYNTHETIC_BASE + "0");
                for (int i = 0; i < concepts; i++) {
                    var concept = asserted.createResource(SYNTHETIC_BASE + i);
                    require(!asserted.containsResource(concept), "synthetic concept IRI must be unused");
                    concept.addProperty(RDF.type, SKOS.Concept)
                            .addProperty(SKOS.prefLabel, "Synthetic benchmark item " + i, "en");
                    if (i > 0) concept.addProperty(SKOS.broader, root);
                }
                for (String graph : RdfReleaseVerifier.GRAPHS) {
                    counts.put(graph, dataset.getNamedModel(publication.namespace() + "graph/" + graph).size());
                }
                try (var output = Files.newOutputStream(target.resolve("formal-sciences.ttl"))) {
                    RDFDataMgr.write(output, asserted, Lang.TURTLE);
                }
                try (var output = Files.newOutputStream(target.resolve("formal-sciences.trig"))) {
                    RDFDataMgr.write(output, dataset, Lang.TRIG);
                }
                try (var output = Files.newOutputStream(target.resolve("formal-sciences.nq"))) {
                    RDFDataMgr.write(output, dataset, Lang.NQUADS);
                }
                dataset.commit();
            } finally {
                dataset.end();
            }
        } finally {
            dataset.close();
        }
        Files.copy(source.resolve("source-alignments.trig"), target.resolve("source-alignments.trig"));
        Files.writeString(target.resolve("validation-report.json"), """
                {"conforms":true,"profile":"Consumer benchmark distribution only. Not producer SHACL validation."}
                """);
        for (String file : RdfReleaseVerifier.FILES) {
            artifacts.put(file, RdfReleaseVerifier.sha256(target.resolve(file)));
        }
        Files.writeString(target.resolve("manifest.json"), json.writeValueAsString(manifest));
    }

    private static void neighborhood(RdfKnowledgeExplorer explorer, int operation, int depth) {
        String iri = RdfReleaseStore.BASE + "concept/" + CONCEPTS.get(operation % CONCEPTS.size());
        var graph = explorer.neighborhood(iri, depth);
        require(!graph.edges().isEmpty(), "neighborhood edges");
    }

    private static void verify(RdfReleaseStore.Status status, String digest) {
        require(status.available() && digest.equals(status.manifestSha256()), "active manifest");
        require(status.concepts() > 0 && status.namedGraphs().size() == 4, "release metadata");
    }

    private static void describe(RdfReleaseStore.Status status) {
        long quads = status.namedGraphs().values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("# Release %s, commit %s, manifest %s, concepts %d, named quads %d%n",
                status.version(), status.sourceCommit(), status.manifestSha256(), status.concepts(), quads);
    }

    private static void samples(String stage, int count, IntConsumer operation) {
        long allocated = allocatedBytes();
        long started = System.nanoTime();
        long[] results = latencies(count, operation);
        emit(stage, results, System.nanoTime() - started, allocatedBytes() - allocated);
    }

    private static long[] latencies(int count, IntConsumer operation) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            operation.accept(i);
            result[i] = System.nanoTime() - started;
        }
        return result;
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    private static <T> T timed(String stage, IoOperation<T> operation) throws IOException {
        long allocated = allocatedBytes();
        long started = System.nanoTime();
        T result = operation.run();
        long elapsed = System.nanoTime() - started;
        emit(stage, new long[]{elapsed}, elapsed, allocatedBytes() - allocated);
        return result;
    }

    private static long allocatedBytes() {
        long allocated = ALLOCATIONS.getTotalThreadAllocatedBytes();
        if (allocated < 0) throw new IllegalStateException("Cannot read process allocation counter");
        return allocated;
    }

    private static String peakRss() {
        try (var fields = Files.lines(Path.of("/proc/self/status"))) {
            return fields.filter(line -> line.startsWith("VmHWM:"))
                    .map(line -> line.split("\\s+")[1]).findFirst().orElse("");
        } catch (IOException unavailable) {
            return "";
        }
    }

    private static void emit(String stage, long[] results, long elapsed, long allocated) {
        Arrays.sort(results);
        System.out.printf(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%.2f,%d,%s%n", stage, results.length,
                elapsed / 1_000_000.0, percentile(results, 0.50) / 1_000_000.0,
                percentile(results, 0.95) / 1_000_000.0, results.length * 1_000_000_000.0 / elapsed,
                allocated, peakRss());
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[(int) Math.ceil(fraction * sorted.length) - 1];
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException("Benchmark workload failed: " + check);
    }
}
