package live.curricula.api.rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.apache.jena.query.QueryExecutionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Tests against checksum-pinned, actual producer RDF artifacts (never mock knowledge). */
class RdfReleaseIntegrationTests {
    static final String PINNED_COMMIT = "abbef1de99953316bcd0663f50f3a88a20d38c11";
    static final String PINNED_MANIFEST = "4cf71b7d381034596432d4e373dfa8d4509412f98d700b43c47d13d493284b42";
    static final String COMMIT_050 = "cbdf2c4de01f561a0af7e9be0a6db4e8d1fe577a";
    static final String MANIFEST_050 = "c7ffe29c6b34ee974187c77b73db737cbd7eb848c641daf7db963fc623173f51";
    static final String COMMIT_060 = "2da2f7676dd1a68fc8dc01427a5240882c7f951a";
    static final String MANIFEST_060 = "ed70ec240ee39fc1b10026848b4cc4b561d50e8344323c1d69e71d8a5b337b54";
    static final String COMMIT_070 = "ed04aa8c2a39ddc89203c3905381a88d489733dc";
    static final String MANIFEST_070 = "0f76ecfd7e9cf2e6d92d6d76b0d09523c34cf5e776442760e2f7109610424162";
    @TempDir Path temp;

    private Path fixture() {
        return fixture("CURRICULA_RDF_FIXTURE", "target/ontology-fixture");
    }

    private Path fixture050() {
        return fixture("CURRICULA_RDF_FIXTURE_050", "target/ontology-fixture-0.5.0");
    }

    private Path fixture060() {
        return fixture("CURRICULA_RDF_FIXTURE_060", "target/ontology-fixture-0.6.0");
    }

    private static Path fixture(String variable, String defaultPath) {
        String configured = System.getenv(variable);
        if (configured != null) {
            Path path = Path.of(configured);
            assertTrue(Files.isDirectory(path), variable + " must point to an existing fixture");
            return path;
        }
        Path path = Path.of(defaultPath);
        Assumptions.assumeTrue(Files.isDirectory(path), "Fetch immutable fixtures with scripts/fetch-integration-fixture.sh");
        return path;
    }

    @Test void realPackageAndScientificQueries() throws Exception {
        Path packageDir = fixture();
        var verified = RdfReleaseVerifier.verify(packageDir, PINNED_MANIFEST);
        assertEquals("curricula.rdf-release/v1", RdfReleaseVerifier.CONTRACT);
        assertEquals("0.4.0", verified.version());
        try (var store = new RdfReleaseStore(temp.resolve("store"))) {
            var publication = store.ingest(packageDir, PINNED_MANIFEST, PINNED_COMMIT);
            assertTrue(publication.available());
            assertEquals(verified.graphCounts(), publication.namedGraphs());
            var catalog = new JenaKnowledgeCatalog(store);
            var dpll = catalog.findBySlug("dpll-algorithm").orElseThrow();
            assertEquals(RdfReleaseStore.BASE + "concept/dpll-algorithm", dpll.iri());
            assertFalse(catalog.search("DPLL", 100).isEmpty());
            assertFalse(catalog.browse("logic", 0, 20).isEmpty());
            var explorer = new RdfKnowledgeExplorer(store);
            var edges = explorer.relationships(dpll.iri(), "asserted", 100);
            assertTrue(edges.stream().anyMatch(e -> e.predicate().endsWith("/solves")
                    && e.subject().equals(dpll.iri()) && e.object().endsWith("/boolean-satisfiability")));
            assertTrue(edges.stream().anyMatch(e -> e.predicate().endsWith("/uses")
                    && e.subject().equals(dpll.iri()) && e.object().endsWith("/unit-propagation")));
            assertTrue(explorer.provenance(dpll.iri(), 100).stream()
                    .anyMatch(e -> e.predicate() != null && e.predicate().endsWith("/solves")));
            assertTrue(explorer.neighborhood(dpll.iri(), 2).nodes().size() > 1);

            store.read(dataset -> {
                assertTrue(dataset.getDefaultModel().isEmpty());
                String query = """
                    PREFIX c: <https://curricula.live/id/formal-sciences/concept/>
                    PREFIX v: <https://curricula.live/id/formal-sciences/vocab/>
                    PREFIX g: <https://curricula.live/id/formal-sciences/graph/>
                    SELECT ?algorithm ?problem FROM g:asserted WHERE {
                        ?algorithm v:uses c:unit-propagation ; v:solves ?problem .
                        ?problem v:hasProperty c:np-completeness .
                    }
                    """;
                try (var q = QueryExecutionFactory.create(query, dataset)) {
                    var results = q.execSelect();
                    boolean found = false;
                    while (results.hasNext()) {
                        var row = results.next();
                        if (dpll.iri().equals(row.getResource("algorithm").getURI())) found = true;
                    }
                    assertTrue(found, "Source SAT competency query must find DPLL");
                }
                return null;
            });
        }
    }

    @Test void rejectDigestCorruptionAndKeepActiveRelease() throws Exception {
        Path source = fixture();
        Path modified = clonePackage(source, temp.resolve("corrupted"));
        Files.writeString(modified.resolve("formal-sciences.ttl"), "tampered data");
        try (var store = new RdfReleaseStore(temp.resolve("store"))) {
            store.ingest(source, PINNED_MANIFEST, PINNED_COMMIT);
            assertThrows(IOException.class, () -> store.ingest(modified, PINNED_MANIFEST, PINNED_COMMIT));
            assertEquals(PINNED_MANIFEST, store.status().manifestSha256());
            assertTrue(new JenaKnowledgeCatalog(store).findBySlug("dpll-algorithm").isPresent());
        }
    }

    @Test void incompatibleContractAndFailedGraphValidationDoNotActivate() throws Exception {
        Path source = fixture();
        Path modified = clonePackage(source, temp.resolve("invalid"));
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(modified.resolve("manifest.json")));
        manifest.put("contract", "curricula.rdf-release/v2");
        Files.writeString(modified.resolve("manifest.json"), json.writeValueAsString(manifest));
        String hash = RdfReleaseVerifier.sha256(modified.resolve("manifest.json"));
        String invalidHash = hash;
        assertThrows(IOException.class, () -> RdfReleaseVerifier.verify(modified, invalidHash));

        manifest.put("contract", "curricula.rdf-release/v1");
        ObjectNode counts = (ObjectNode) manifest.path("counts");
        counts.put("asserted", counts.path("asserted").asLong() + 1);
        Files.writeString(modified.resolve("manifest.json"), json.writeValueAsString(manifest));
        hash = RdfReleaseVerifier.sha256(modified.resolve("manifest.json"));
        try (var store = new RdfReleaseStore(temp.resolve("store"))) {
            store.ingest(source, PINNED_MANIFEST, PINNED_COMMIT);
            String expectedHash = hash;
            assertThrows(IOException.class, () -> store.ingest(modified, expectedHash, PINNED_COMMIT));
            assertEquals(PINNED_MANIFEST, store.status().manifestSha256());
        }
    }

    @Test void immutableUpgradeRollbackReactivationAndRestart() throws Exception {
        Path v040 = fixture();
        Path v050 = fixture050();
        Path v060 = fixture060();
        Path storeDir = temp.resolve("store");
        var stableSlug = "dpll-algorithm";
        try (var store = new RdfReleaseStore(storeDir)) {
            assertImported(store, v040, PINNED_MANIFEST, PINNED_COMMIT, "0.4.0");
            var catalog = new JenaKnowledgeCatalog(store);
            var dpll = catalog.findBySlug(stableSlug).orElseThrow().iri();
            Set<String> concepts040 = concepts(store);
            assertFalse(catalog.findBySlug("mathmoddb-q6775745").isPresent());

            assertImported(store, v050, MANIFEST_050, COMMIT_050, "0.5.0");
            Set<String> concepts050 = concepts(store);
            assertTrue(concepts050.containsAll(concepts040));
            assertTrue(concepts050.size() > concepts040.size());
            assertEquals(dpll, catalog.findBySlug(stableSlug).orElseThrow().iri());
            assertScientificEdge(store, "mathmoddb-q6775745", "isSolutionOf", "mathmoddb-q6675406");

            assertEquals(PINNED_MANIFEST, store.rollback().manifestSha256());
            assertFalse(catalog.findBySlug("mathmoddb-q6775745").isPresent());
        }
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(PINNED_MANIFEST, store.status().manifestSha256());
            // Rollback swaps the pointers, so the old candidate stays available.
            assertEquals(MANIFEST_050, store.rollback().manifestSha256());
            Set<String> concepts050 = concepts(store);
            assertImported(store, v060, MANIFEST_060, COMMIT_060, "0.6.0");
            Set<String> concepts060 = concepts(store);
            assertTrue(concepts060.containsAll(concepts050));
            assertTrue(concepts060.size() > concepts050.size());
            assertScientificEdge(store, "mls-model-evaluation-result", "reportsEvaluationMeasure", "mls-evaluation-measure");
        }
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(MANIFEST_060, store.status().manifestSha256());
            assertEquals(MANIFEST_050, store.rollback().manifestSha256());
            assertEquals(MANIFEST_060, store.ingest(v060, MANIFEST_060, COMMIT_060).manifestSha256());
        }
    }

    @Test void upgradesToLatestProducerFixture() throws Exception {
        Path v060 = fixture060();
        Path v070 = fixture("CURRICULA_RDF_FIXTURE_070", "target/ontology-fixture-0.7.0");
        Path storeDir = temp.resolve("latest-store");
        try (var store = new RdfReleaseStore(storeDir)) {
            store.ingest(v060, MANIFEST_060, COMMIT_060);
            Set<String> concepts060 = concepts(store);
            assertImported(store, v070, MANIFEST_070, COMMIT_070, "0.7.0");
            Set<String> concepts070 = concepts(store);
            assertTrue(concepts070.containsAll(concepts060));
            assertTrue(concepts070.size() > concepts060.size());
            assertScientificEdge(store, "fixed-input-halting-rice-proof", "proves",
                    "fixed-input-halting-undecidability");
        }
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(MANIFEST_070, store.status().manifestSha256());
            assertEquals(MANIFEST_060, store.rollback().manifestSha256());
        }
    }

    @Test void importsReviewedComputingEvidenceFrom080() throws Exception {
        Path current = fixture("CURRICULA_RDF_FIXTURE_080", "target/ontology-fixture-0.8.0");
        String digest = "25152ce02ee0b6b0cf6039b74d9d5d41cb058571cb05097845d86eb88bbaf8ba";
        String commit = "17c4c1db6901381b41bd377fb8c24c2f868c1f23";
        try (var store = new RdfReleaseStore(temp.resolve("store080"))) {
            store.ingest(fixture("CURRICULA_RDF_FIXTURE_070", "target/ontology-fixture-0.7.0"),
                    MANIFEST_070, COMMIT_070);
            Set<String> preceding = concepts(store);
            assertImported(store, current, digest, commit, "0.8.0");
            var now = concepts(store);
            assertTrue(now.containsAll(preceding));
            assertTrue(now.size() > preceding.size());
            assertScientificEdge(store, "binary-search-tree", "supportsOperation", "rank-in-ordered-set");
            assertEquals(MANIFEST_070, store.rollback().manifestSha256());
        }
    }

    @Test void abruptExitLeavesAnEntireOldOrNewSelection() throws Exception {
        Path source = fixture();
        Path updated = fixture050();
        Path storeDir = temp.resolve("crash-store");
        try (var store = new RdfReleaseStore(storeDir)) {
            store.ingest(source, PINNED_MANIFEST, PINNED_COMMIT);
            assertThrows(IOException.class, () -> new RdfReleaseStore(storeDir));
            assertProcessExit(92, storeDir.toString());
        }

        haltDuringImport(storeDir, updated, RdfReleaseStore.Transition.RELEASE_PROMOTED);
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(PINNED_MANIFEST, store.status().manifestSha256());
            assertThrows(IOException.class, store::rollback);
        }
        haltDuringImport(storeDir, updated, RdfReleaseStore.Transition.BEFORE_SELECTION_REPLACE);
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(PINNED_MANIFEST, store.status().manifestSha256());
            assertThrows(IOException.class, store::rollback);
        }
        haltDuringImport(storeDir, updated, RdfReleaseStore.Transition.AFTER_SELECTION_REPLACE);
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(MANIFEST_050, store.status().manifestSha256());
            assertEquals(PINNED_MANIFEST, store.rollback().manifestSha256());
            assertEquals(MANIFEST_050, store.rollback().manifestSha256());
        }

        // Simulate a store left by the older Java implementation.
        Files.writeString(storeDir.resolve("active.txt"), MANIFEST_050);
        Files.writeString(storeDir.resolve("previous.txt"), PINNED_MANIFEST);
        Files.delete(storeDir.resolve("release-state"));
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(MANIFEST_050, store.status().manifestSha256());
            assertEquals(PINNED_MANIFEST, store.rollback().manifestSha256());
        }
        try (var store = new RdfReleaseStore(storeDir)) {
            assertEquals(PINNED_MANIFEST, store.status().manifestSha256());
            assertEquals(MANIFEST_050, store.rollback().manifestSha256());
        }
    }

    @Test void fetchVerifiesManifestBeforeArtifactsAndNeverPublishesPartialPackage() throws Exception {
        Path source = fixture();
        Path destination = temp.resolve("downloaded");
        URI base = URI.create("https://example.test/immutable/");
        var downloaded = new ArrayList<String>();
        var fetcher = new RdfPublicationFetcher((uri, target) -> {
            String file = base.relativize(uri).getPath();
            downloaded.add(file);
            Files.copy(source.resolve(file), target);
        });
        assertThrows(IOException.class, () -> fetcher.fetch(base, MANIFEST_050, destination));
        assertEquals(java.util.List.of("manifest.json"), downloaded);
        assertFalse(Files.exists(destination));
        downloaded.clear();

        var interrupted = new RdfPublicationFetcher((uri, target) -> {
            String file = base.relativize(uri).getPath();
            if (file.endsWith(".nq")) throw new InterruptedException("Interrupted download");
            Files.copy(source.resolve(file), target);
        });
        assertThrows(InterruptedException.class, () -> interrupted.fetch(base, PINNED_MANIFEST, destination));
        try (var leftovers = Files.list(temp)) {
            assertEquals(0, leftovers.count());
        }
        assertEquals(destination, fetcher.fetch(base, PINNED_MANIFEST, destination));
        assertEquals(6, downloaded.size());
        downloaded.clear();
        fetcher.fetch(base, PINNED_MANIFEST, destination);
        assertTrue(downloaded.isEmpty());
        assertThrows(IOException.class, () -> fetcher.fetch(base, MANIFEST_050, destination));
        assertEquals(PINNED_MANIFEST, RdfReleaseVerifier.sha256(destination.resolve("manifest.json")));
    }

    private static void assertImported(RdfReleaseStore store, Path path, String digest, String commit, String version)
            throws IOException {
        var verified = RdfReleaseVerifier.verify(path, digest);
        var status = store.ingest(path, digest, commit);
        assertEquals(version, status.version());
        assertEquals(commit, status.sourceCommit());
        assertEquals(verified.concepts(), concepts(store).size());
        assertEquals(verified.concepts(), status.concepts());
        assertEquals(verified.graphCounts(), status.namedGraphs());
        assertEquals(Set.copyOf(RdfReleaseVerifier.GRAPHS), status.namedGraphs().keySet());
        Path queryDir = path.resolveSibling(path.getFileName() + "-queries");
        try (var files = Files.list(queryDir)) {
            var queries = files.filter(file -> file.toString().endsWith(".rq")).sorted().toList();
            assertFalse(queries.isEmpty(), "Missing producer-supplied competency queries");
            for (Path file : queries) {
                String query = Files.readString(file);
                store.read(dataset -> {
                    try (var q = QueryExecutionFactory.create(query, dataset)) {
                        var results = q.execSelect();
                        assertTrue(results.hasNext(), file + " must produce a witness");
                        while (results.hasNext()) results.next();
                    }
                    return null;
                });
            }
        }
    }

    private static Set<String> concepts(RdfReleaseStore store) {
        return store.read(dataset -> {
            var result = new HashSet<String>();
            var resources = dataset.getNamedModel(RdfReleaseStore.BASE + "graph/asserted")
                    .listResourcesWithProperty(RDF.type, SKOS.Concept);
            try {
                resources.forEachRemaining(resource -> result.add(resource.getURI()));
            } finally {
                resources.close();
            }
            return result;
        });
    }

    private static void assertScientificEdge(RdfReleaseStore store, String source, String predicate, String target) {
        var catalog = new JenaKnowledgeCatalog(store);
        var explorer = new RdfKnowledgeExplorer(store);
        String from = catalog.findBySlug(source).orElseThrow().iri();
        String to = catalog.findBySlug(target).orElseThrow().iri();
        String relation = RdfReleaseStore.BASE + "vocab/" + predicate;
        assertTrue(explorer.relationships(from, "asserted", 100).stream()
                .anyMatch(edge -> edge.subject().equals(from) && edge.object().equals(to)
                        && edge.predicate().equals(relation)));
        assertTrue(explorer.provenance(from, 100).stream().anyMatch(item -> relation.equals(item.predicate())));
    }

    private void haltDuringImport(Path store, Path updated, RdfReleaseStore.Transition transition) throws Exception {
        assertProcessExit(91, store.toString(), updated.toAbsolutePath().toString(), transition.name());
    }

    private void assertProcessExit(int code, String... args) throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java");
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = Files.createTempFile(temp, "rdf-child-", ".log");
        var command = new ArrayList<>(java.util.List.of(executable.toString(), "-cp", classpath, CrashProcess.class.getName()));
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Timed out waiting for RDF child process");
            assertEquals(code, process.exitValue(), () -> readLog(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return e.toString();
        }
    }

    static class CrashProcess {
        public static void main(String[] args) throws Exception {
            if (args.length == 1) {
                try (var store = new RdfReleaseStore(Path.of(args[0]))) {
                    throw new AssertionError("A second process opened the RDF store");
                } catch (IOException e) {
                    if (!e.getMessage().startsWith("RDF store is already open:")) throw e;
                    System.exit(92);
                }
            }
            var target = RdfReleaseStore.Transition.valueOf(args[2]);
            try (var store = new RdfReleaseStore(Path.of(args[0]), transition -> {
                if (transition == target) Runtime.getRuntime().halt(91);
            })) {
                store.ingest(Path.of(args[1]), MANIFEST_050, COMMIT_050);
            }
        }
    }

    private static Path clonePackage(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        for (var name : RdfReleaseVerifier.FILES) {
            Files.copy(source.resolve(name), destination.resolve(name));
        }
        Files.copy(source.resolve("manifest.json"), destination.resolve("manifest.json"));
        return destination;
    }
}
