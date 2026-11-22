package live.curricula.api.rdf;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.education.RdfEducationCatalog;
import org.apache.jena.query.QueryExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MultiProducerIntegrationTests {
    private static final String CONTRACT = "curricula.rdf-release/v1";
    private static final String SYNTHETIC_COMMIT = "0".repeat(40);
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final RdfOntologyRegistry.Producer FIRST = RdfOntologyRegistry.FORMAL_SCIENCES;
    private static final RdfOntologyRegistry.Producer SECOND = new RdfOntologyRegistry.Producer(
            "learning-fixture", "https://example.test/ontology/learning-fixture/");
    private static final String EDGE = SECOND.namespace() + "vocab/recordedTestConnection";
    private static final RdfOntologyRegistry REGISTRY = new RdfOntologyRegistry(List.of(FIRST, SECOND));

    @TempDir Path temp;

    @Test void activatesAndQueriesSeparateProducersThenRollsBackOnlyOneAcrossRestart() throws Exception {
        var a = fixture(FIRST, "test-1", "First test concept", false);
        var b1 = fixture(SECOND, "test-1", "Second test concept", true);
        var b2 = fixture(SECOND, "test-2", "Second test revision", true);
        Path storage = temp.resolve("isolated-stores");
        try (var store = new RdfPublications(storage, REGISTRY)) {
            store.ingest(SECOND.id(), b1.dir(), b1.hash(), SYNTHETIC_COMMIT);
            assertFalse(store.status().available());
            assertTrue(store.available());
            assertThrows(IOException.class, () -> store.ingest(FIRST.id(), b1.dir(), b1.hash(), SYNTHETIC_COMMIT));
            store.ingest(FIRST.id(), a.dir(), a.hash(), SYNTHETIC_COMMIT);
            store.ingest(SECOND.id(), b2.dir(), b2.hash(), SYNTHETIC_COMMIT);
            assertCatalogAndCrossGraphQuery(store, "Second test revision");
            assertEquals(a.hash(), store.status().manifestSha256());
        }

        try (var store = new RdfPublications(storage, REGISTRY)) {
            assertEquals(b2.hash(), store.statuses().get(SECOND.id()).manifestSha256());
            assertEquals(b1.hash(), store.rollback(SECOND.id()).manifestSha256());
            assertCatalogAndCrossGraphQuery(store, "Second test concept");
            assertEquals(a.hash(), store.status().manifestSha256());
        }
    }

    private static void assertCatalogAndCrossGraphQuery(RdfPublications store, String secondLabel) {
        String first = FIRST.conceptIri("shared");
        String second = SECOND.conceptIri("shared");
        var catalog = new JenaKnowledgeCatalog(store, REGISTRY);
        assertEquals(first, catalog.findBySlug("shared").orElseThrow().iri());
        assertEquals(secondLabel, catalog.findByIri(second).orElseThrow().label());
        assertEquals(java.util.Set.of(first, second), catalog.search("shared", 10, "all").stream()
                .map(item -> item.iri()).collect(java.util.stream.Collectors.toSet()));
        var education = new RdfEducationCatalog(store, REGISTRY, SECOND.id()).context(first);
        assertTrue(education.available());
        assertEquals(List.of(second), education.prerequisites().stream().map(item -> item.value()).toList());
        assertEquals("Test note", education.teachingNotes().getFirst().value());
        assertEquals("https://example.test/test-standard", education.curriculumMappings().getFirst().value());
        assertTrue(new RdfEducationCatalog(store, REGISTRY, SECOND.id()).context(second).prerequisites().isEmpty());
        assertFalse(new RdfEducationCatalog(store, REGISTRY, "").context(first).available());
        var graph = new RdfKnowledgeExplorer(store, REGISTRY).neighborhood(first, 1);
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.subject().equals(second)
                && edge.object().equals(first) && edge.predicate().equals(EDGE)));

        String sparql = """
                ASK WHERE {
                  GRAPH <%s> { <%s> <%s> <%s> }
                  GRAPH <%s> { <%s> a <%sConcept> }
                }
                """.formatted(SECOND.graphIri("asserted"), second, EDGE, first,
                FIRST.graphIri("asserted"), first, SKOS);
        boolean recorded = store.read(dataset -> {
            try (var query = QueryExecution.dataset(dataset).query(sparql).build()) {
                return query.execAsk();
            }
        });
        assertTrue(recorded);
    }

    private Fixture fixture(RdfOntologyRegistry.Producer producer, String version, String label, boolean edge)
            throws IOException {
        Path dir = Files.createDirectory(temp.resolve(producer.id() + "-" + version));
        String graph = iri(producer.graphIri("asserted"));
        String subject = iri(producer.conceptIri("shared"));
        var triples = new java.util.ArrayList<>(List.of(
                subject + " " + iri(RDF_TYPE) + " " + iri(SKOS + "Concept"),
                subject + " " + iri(SKOS + "prefLabel") + " \"" + label + "\"@en"));
        if (edge) {
            String target = iri(FIRST.conceptIri("shared"));
            triples.add(subject + " " + iri(EDGE) + " " + target);
            triples.add(subject + " " + iri(RdfEducationCatalog.VOCAB + "prerequisiteOf") + " " + target);
            triples.add(target + " " + iri(RdfEducationCatalog.VOCAB + "curriculumMapping") + " <https://example.test/test-standard>");
            triples.add(target + " " + iri(RdfEducationCatalog.VOCAB + "teachingNote") + " \"Test note\"@en");
        }
        Files.writeString(dir.resolve(producer.id() + ".ttl"), String.join(" .\n", triples) + " .\n");
        Files.writeString(dir.resolve(producer.id() + ".trig"), graph + " {\n" + String.join(" .\n", triples) + " .\n}\n");
        Files.writeString(dir.resolve(producer.id() + ".nq"),
                String.join("\n", triples.stream().map(triple -> triple + " " + graph + " .").toList()) + "\n");
        Files.writeString(dir.resolve("source-alignments.trig"), "# No reviewed alignments in this isolated fixture\n");
        Files.writeString(dir.resolve("validation-report.json"), "{\"conforms\":true,\"profile\":\"Synthetic consumer test only\"}");
        Map<String, String> artifacts = new LinkedHashMap<>();
        for (String filename : RdfReleaseVerifier.files(producer)) {
            artifacts.put(filename, RdfReleaseVerifier.sha256(dir.resolve(filename)));
        }
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, JsonMapper.builder().build().writeValueAsString(Map.of(
                "contract", CONTRACT, "namespace", producer.namespace(), "conforms", true,
                "artifacts", artifacts, "concepts", 1, "version", version,
                "counts", Map.of("asserted", triples.size(), "alignments", 0, "inferred", 0, "provenance", 0))));
        return new Fixture(dir, RdfReleaseVerifier.sha256(manifest));
    }

    private static String iri(String value) { return "<" + value + ">"; }

    private record Fixture(Path dir, String hash) { }
}
