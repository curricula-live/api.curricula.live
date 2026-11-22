package live.curricula.api.rdf;

import static live.curricula.api.rdf.RdfOntologyRegistry.FORMAL_SCIENCES;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.function.Function;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.http.ApiInputProblemHandler;
import live.curricula.api.http.RdfApiController;
import live.curricula.api.rdf.RdfKnowledgeExplorer.RelationshipFilter;
import live.curricula.api.rdf.RdfOntologyRegistry.Producer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RdfKnowledgeExplorerTests {
    private static final String ROOT = FORMAL_SCIENCES.conceptIri("root");
    private static final String SCIENTIFIC = FORMAL_SCIENCES.namespace() + "vocab/example";
    private static final String DOMAIN = FORMAL_SCIENCES.schemeIri("logic");
    private static final Producer OTHER = new Producer("other", "https://test.example/ontology/");
    private static final RelationshipFilter ALL = new RelationshipFilter("all", "all", null, null);

    private Dataset dataset;
    private RdfReleaseStore store;
    private RdfKnowledgeExplorer explorer;

    @BeforeEach void setup() {
        dataset = DatasetFactory.createTxnMem();
        store = mock(RdfReleaseStore.class);
        when(store.read(any())).thenAnswer(invocation -> {
            Function<Dataset, ?> query = invocation.getArgument(0);
            dataset.begin(ReadWrite.READ);
            try {
                return query.apply(dataset);
            } finally {
                dataset.end();
            }
        });
        when(store.readFormal(any())).thenAnswer(invocation -> store.read(invocation.getArgument(0)));
        explorer = new RdfKnowledgeExplorer(store);
        populate(() -> concept(dataset.getNamedModel(FORMAL_SCIENCES.graphIri("asserted")), ROOT, "Root"));
    }

    @AfterEach void close() {
        dataset.close();
    }

    @Test void orderAndOffsetCoverAllMatchingAdjacenciesBeforeApplyingLimit() {
        populate(() -> {
            var asserted = dataset.getNamedModel(FORMAL_SCIENCES.graphIri("asserted"));
            var root = asserted.getResource(ROOT);
            var predicate = asserted.createProperty(SCIENTIFIC);
            for (int i = 124; i >= 0; i--) {
                String slug = "target-%03d".formatted(i);
                var target = concept(asserted, FORMAL_SCIENCES.conceptIri(slug), slug);
                asserted.add(root, predicate, target);
            }
            var earliest = concept(asserted, FORMAL_SCIENCES.conceptIri("a"), "Incoming");
            asserted.add(earliest, predicate, root);
            asserted.add(root, predicate, root);
        });

        var one = explorer.relationships(ROOT, ALL, 0, 1).getFirst();
        assertEquals("incoming", one.direction());
        assertEquals(FORMAL_SCIENCES.conceptIri("a"), one.subject());
        var full = explorer.relationships(ROOT, ALL, 0, 500);
        assertEquals(127, full.size(), "Self assertions are returned once");
        assertEquals(full.subList(100, 110), explorer.relationships(ROOT, ALL, 100, 10));
        assertTrue(explorer.relationships(ROOT, ALL, 127, 1).isEmpty());
        assertEquals(full, explorer.relationships(ROOT, ALL, 0, 500));
    }

    @Test void filtersAndTraversalPreserveExactDirectionAndDoNotDuplicateStatements() {
        String logic = FORMAL_SCIENCES.conceptIri("logic-target");
        String mathematics = FORMAL_SCIENCES.conceptIri("math-target");
        populate(() -> {
            Model asserted = dataset.getNamedModel(FORMAL_SCIENCES.graphIri("asserted"));
            var root = asserted.getResource(ROOT);
            var a = concept(asserted, logic, "Logic");
            var b = concept(asserted, mathematics, "Mathematics");
            asserted.add(a, SKOS.inScheme, asserted.getResource(DOMAIN));
            asserted.add(b, SKOS.inScheme, asserted.getResource(FORMAL_SCIENCES.schemeIri("mathematics")));
            asserted.add(root, asserted.createProperty(SCIENTIFIC), a);
            asserted.add(root, SKOS.broader, b);
            asserted.add(a, asserted.createProperty(SCIENTIFIC), b);
            var inferred = dataset.getNamedModel(FORMAL_SCIENCES.graphIri("inferred"));
            inferred.add(root, SKOS.related, a);
        });

        var filtered = explorer.neighborhood(ROOT, 1,
                new RelationshipFilter("asserted", "scientific", SCIENTIFIC, DOMAIN), 20);
        assertEquals(List.of(ROOT, logic), filtered.nodes());
        assertEquals("outgoing", filtered.edges().getFirst().direction());
        assertEquals(FORMAL_SCIENCES.graphIri("asserted"), filtered.edges().getFirst().graphIri());
        assertEquals(List.of(DOMAIN), filtered.nodeDetails().getLast().domains());
        assertEquals("logic-target", filtered.nodeDetails().getLast().slug());
        assertEquals(1, explorer.neighborhood(ROOT, 1,
                new RelationshipFilter("inferred", "all", null, "logic"), 20).edges().size());
        assertEquals(4, explorer.neighborhood(ROOT, 2, ALL, 4).edges().size());
        assertFalse(explorer.neighborhood(ROOT, 2, ALL, 4).truncated());
        assertTrue(explorer.neighborhood(ROOT, 2, ALL, 3).truncated());
    }

    @Test void namespacesPreserveExactIdentityAndCrossSourceEvidence() throws Exception {
        String otherRoot = OTHER.conceptIri("root");
        String assertion = OTHER.namespace() + "assertion/test";
        populate(() -> {
            concept(dataset.getNamedModel(OTHER.graphIri("asserted")), otherRoot, "Other root");
            Model align = dataset.getNamedModel(OTHER.graphIri("alignments"));
            align.add(align.getResource(otherRoot), SKOS.closeMatch, align.getResource(ROOT));
            Model evidence = dataset.getNamedModel(OTHER.graphIri("provenance"));
            var statement = evidence.getResource(assertion);
            evidence.add(statement, RDF.subject, evidence.getResource(otherRoot));
            evidence.add(statement, RDF.predicate, SKOS.closeMatch);
            evidence.add(statement, RDF.object, evidence.getResource(ROOT));
        });

        var registry = new RdfOntologyRegistry(List.of(FORMAL_SCIENCES, OTHER));
        assertThrows(IllegalArgumentException.class, () -> new RdfOntologyRegistry(List.of(
                FORMAL_SCIENCES, new Producer("nested", FORMAL_SCIENCES.namespace() + "nested/"))));
        var catalog = new JenaKnowledgeCatalog(store, registry);
        assertEquals(ROOT, catalog.findBySlug("root").orElseThrow().iri());
        assertEquals("Other root", catalog.findByIri(otherRoot).orElseThrow().label());
        var crossSource = new RdfKnowledgeExplorer(store, registry);
        var edge = crossSource.neighborhood(ROOT, 1, ALL, 10).edges().getFirst();
        assertEquals(otherRoot, edge.subject());
        assertEquals("incoming", edge.direction());
        assertEquals(OTHER.graphIri("alignments"), edge.graphIri());
        var provenance = crossSource.provenance(ROOT, 10, "incoming", SKOS.closeMatch.getURI(), otherRoot);
        assertEquals(assertion, provenance.getFirst().assertionIri());
        assertEquals(OTHER.graphIri("provenance"), provenance.getFirst().graphIri());
        assertEquals(otherRoot, provenance.getFirst().subject());
        assertTrue(crossSource.provenance(ROOT, 10).isEmpty());

        var mvc = MockMvcBuilders.standaloneSetup(new RdfApiController(catalog, crossSource, store))
                .setControllerAdvice(new ApiInputProblemHandler()).build();
        mvc.perform(get("/api/v1/concepts/by-iri/neighborhood").param("iri", otherRoot)
                        .param("graph", "alignments").param("kind", "mapping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root").value(otherRoot))
                .andExpect(jsonPath("$.edges[0].subject").value(otherRoot));
        mvc.perform(get("/api/v1/concepts/root/relationships").param("offset", "10001"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/concepts/root/neighborhood").param("predicate", "relative/iri"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/concepts/absent/neighborhood"))
                .andExpect(status().isNotFound());
    }

    private void populate(Runnable action) {
        dataset.begin(ReadWrite.WRITE);
        try {
            action.run();
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static Resource concept(Model model, String iri, String label) {
        return model.createResource(iri).addProperty(RDF.type, SKOS.Concept)
                .addProperty(SKOS.prefLabel, model.createLiteral(label, "en"));
    }
}
