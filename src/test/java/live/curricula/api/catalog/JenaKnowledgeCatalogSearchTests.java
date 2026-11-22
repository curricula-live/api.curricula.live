package live.curricula.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;
import live.curricula.api.rdf.RdfReleaseStore;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

class JenaKnowledgeCatalogSearchTests {
    private static final String BASE = RdfReleaseStore.BASE;

    @Test void indexedSearchRanksExactLabelsAndRetainsTermFieldsAndDisplayLanguage() {
        Dataset dataset = DatasetFactory.createTxnMem();
        try {
            dataset.begin(ReadWrite.WRITE);
            try {
                Model asserted = dataset.getNamedModel(BASE + "graph/asserted");
                concept(asserted, "z", "zeta", "en").addProperty(SKOS.altLabel, "needle");
                concept(asserted, "b", "Alpha", "en-GB").addProperty(SKOS.definition, "a NEEDLE here");
                concept(asserted, "a", "ALPHA", "").addProperty(SKOS.notation, "needle")
                        .addProperty(SKOS.altLabel, "needle");
                concept(asserted, "c", "Balance", "en")
                        .addProperty(DCTerms.identifier, asserted.createResource("https://example.org/needle"));
                concept(asserted, "d", "needle", "en");
                concept(asserted, "inmultire-polinoame", "Înmulțire polinoame", "en");
                concept(asserted, "foreign", "Foreign", "fr").addProperty(SKOS.altLabel, "needle");
                asserted.createResource(BASE + "concept/no-type")
                        .addProperty(SKOS.altLabel, "needle").addProperty(SKOS.prefLabel, "Ignored");
                concept(dataset.getDefaultModel(), "default", "Ignored", "en")
                        .addProperty(SKOS.altLabel, "needle");
                dataset.commit();
            } finally {
                dataset.end();
            }
            var store = mock(RdfReleaseStore.class);
            when(store.readFormal(any())).thenAnswer(invocation -> {
                Function<Dataset, ?> search = invocation.getArgument(0);
                dataset.begin(ReadWrite.READ);
                try {
                    return search.apply(dataset);
                } finally {
                    dataset.end();
                }
            });
            var catalog = new JenaKnowledgeCatalog(store);
            assertEquals(List.of("d", "a", "z", "b", "c"),
                    catalog.search(" NEEDLE ", 25).stream().map(ConceptSummary::slug).toList());
            assertEquals(List.of("d", "a"),
                    catalog.search("needle", 2).stream().map(ConceptSummary::slug).toList());
            assertEquals(List.of("inmultire-polinoame"),
                    catalog.search("inmultire_polinoame", 25).stream().map(ConceptSummary::slug).toList());
            assertEquals(List.of(), catalog.search("needle\" } UNION {", 25));
        } finally {
            dataset.close();
        }
    }

    private static Resource concept(Model model, String slug, String label, String language) {
        return model.createResource(BASE + "concept/" + slug)
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(SKOS.prefLabel, label, language);
    }
}
