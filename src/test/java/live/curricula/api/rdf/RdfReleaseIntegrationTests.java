package live.curricula.api.rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @TempDir Path temp;

    private Path fixture() {
        String configured = System.getenv("CURRICULA_RDF_FIXTURE");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "Set CURRICULA_RDF_FIXTURE to the immutable v0.4.0 distribution");
        return Path.of(configured);
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

    @Test void immutableActivationRollbackAndRestart() throws Exception {
        Path source = fixture();
        Path updated = clonePackage(source, temp.resolve("second"));
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(updated.resolve("manifest.json")));
        manifest.put("version", "0.4.0-test-reissue");
        Files.writeString(updated.resolve("manifest.json"), json.writeValueAsString(manifest));
        String secondHash = RdfReleaseVerifier.sha256(updated.resolve("manifest.json"));
        Path storeDir = temp.resolve("store");
        try (var store = new RdfReleaseStore(storeDir)) {
            store.ingest(source, PINNED_MANIFEST, PINNED_COMMIT);
            store.ingest(updated, secondHash, PINNED_COMMIT);
            assertEquals(secondHash, store.status().manifestSha256());
            assertEquals(PINNED_MANIFEST, store.rollback().manifestSha256());
        }
        try (var restarted = new RdfReleaseStore(storeDir)) {
            assertEquals(PINNED_MANIFEST, restarted.status().manifestSha256());
            assertTrue(new JenaKnowledgeCatalog(restarted).findBySlug("dpll-algorithm").isPresent());
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
