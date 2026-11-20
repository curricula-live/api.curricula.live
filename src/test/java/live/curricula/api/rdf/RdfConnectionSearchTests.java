package live.curricula.api.rdf;

import static live.curricula.api.rdf.RdfConnectionSearch.execute;
import static live.curricula.api.rdf.RdfConnectionSearch.prepare;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RdfConnectionSearchTests {
    private static final String BASE = RdfReleaseStore.BASE;

    @Test
    void canonicalConnectionsAreDirectedRankedAndBounded() {
        var dataset = DatasetFactory.createTxnMem();
        try {
            Txn.executeWrite(dataset, () -> {
                var asserted = dataset.getNamedModel(BASE + "graph/asserted");
                var alphaSort = concept(asserted.createResource(BASE + "concept/alpha-sort"), "Alpha sort");
                var beta = concept(asserted.createResource(BASE + "concept/beta"), "Beta");
                var zetaSort = concept(asserted.createResource(BASE + "concept/zeta"), "Zeta algorithm");
                zetaSort.addProperty(SKOS.altLabel, "sort", "en");
                zetaSort.addProperty(SKOS.prefLabel, "Zeta în română", "ro");
                var predicate = asserted.createProperty(BASE + "vocab/uses");
                alphaSort.addProperty(predicate, beta);
                zetaSort.addProperty(predicate, beta);
                beta.addProperty(predicate, asserted.createResource("https://example.org/external"));

                var inferred = dataset.getNamedModel(BASE + "graph/inferred");
                inferred.add(alphaSort, predicate, beta);
                dataset.getDefaultModel().add(beta, predicate, zetaSort);
            });
            Txn.executeRead(dataset, () -> {
                var exact = execute(dataset, prepare(" SORT ", 1)).getFirst();
                assertEquals(BASE + "concept/zeta", exact.subject());
                assertEquals("Zeta algorithm", exact.subjectLabel());
                assertEquals(BASE + "concept/beta", exact.object());
                assertEquals(BASE + "vocab/uses", exact.predicate());
                assertEquals("asserted", exact.graph());

                var predicateMatches = execute(dataset, prepare("uses", 100));
                assertEquals(3, predicateMatches.size());
                assertEquals("asserted", predicateMatches.get(0).graph());
                assertEquals("inferred", predicateMatches.get(1).graph());
                assertEquals("zeta", predicateMatches.get(2).subjectSlug());
                assertEquals(2, execute(dataset, prepare("alpha_sort", 100)).size());
                assertTrue(execute(dataset, prepare("sort \" } UNION { ?a ?b ?c", 100)).isEmpty());
            });
        } finally {
            dataset.close();
        }
    }

    @Test
    void findsVerifiedFormalSciencesConnection(@TempDir Path storePath) throws Exception {
        String fixture = System.getenv("CURRICULA_RDF_FIXTURE_050");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null && !fixture.isBlank(),
                "Set CURRICULA_RDF_FIXTURE_050 to the immutable v0.5.0 distribution");
        try (var store = new RdfReleaseStore(storePath)) {
            store.ingest(Path.of(fixture),
                    "c7ffe29c6b34ee974187c77b73db737cbd7eb848c641daf7db963fc623173f51",
                    "cbdf2c4de01f561a0af7e9be0a6db4e8d1fe577a");
            var matches = new RdfConnectionSearch(store).search("DPLL algorithm", 100);
            assertTrue(matches.stream().anyMatch(connection ->
                    connection.subjectSlug().equals("dpll-algorithm")
                            && connection.objectSlug().equals("boolean-satisfiability")
                            && connection.predicate().endsWith("/solves")
                            && connection.graph().equals("asserted")));
        }
    }

    private static Resource concept(Resource concept, String label) {
        return concept.addProperty(RDF.type, SKOS.Concept).addProperty(SKOS.prefLabel, label, "en");
    }
}
