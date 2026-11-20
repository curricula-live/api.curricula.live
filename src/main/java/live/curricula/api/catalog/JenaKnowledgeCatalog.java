package live.curricula.api.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import live.curricula.api.rdf.RdfReleaseStore;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

/** Read-only RDF-native query adapter. Never relies on default-graph unions. */
public final class JenaKnowledgeCatalog implements KnowledgeCatalog {
    private static final String NS = RdfReleaseStore.BASE;
    private static final String ASSERTED = NS + "graph/asserted";
    private final RdfReleaseStore store;

    public JenaKnowledgeCatalog(RdfReleaseStore store) { this.store = store; }

    public static String slugFromIri(String iri) {
        int index = iri.lastIndexOf('/');
        return index < 0 ? iri : iri.substring(index + 1);
    }

    private static ConceptSummary summary(Resource resource, Literal label) {
        return new ConceptSummary(resource.getURI(), slugFromIri(resource.getURI()), label.getString());
    }

    @Override public boolean available() { return store.available(); }

    @Override public List<ConceptSummary> list(int limit) {
        return browse(null, 0, limit);
    }

    public List<ConceptSummary> browse(String domain, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        String sparql = """
                PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
                SELECT DISTINCT ?c ?label WHERE {
                  GRAPH <%s> {
                    ?c a skos:Concept ; skos:prefLabel ?label .
                    FILTER(LANG(?label) = "" || LANGMATCHES(LANG(?label), "en"))
                    %s
                  }
                }
                ORDER BY LCASE(STR(?label)) ?c
                OFFSET %d LIMIT %d
                """.formatted(ASSERTED,
                        (domain == null || domain.isBlank()) ? "" : 
                        "?c skos:inScheme <" + NS + "scheme/" + domain + "> .",
                        offset, limit);
        if (domain != null && !domain.isBlank() && !domain.matches("[a-z][-a-z0-9]*")) {
            throw new IllegalArgumentException("Invalid domain");
        }
        return store.read(dataset -> {
            List<ConceptSummary> list = new ArrayList<>();
            try (var query = QueryExecutionFactory.create(sparql, dataset)) {
                var rs = query.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.nextSolution();
                    list.add(summary(row.getResource("c"), row.getLiteral("label")));
                }
            }
            return list;
        });
    }

    @Override public List<ConceptSummary> search(String text, int limit) {
        if (text == null || text.isBlank() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid search query or limit");
        }
        String sparql = """
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            PREFIX dct: <http://purl.org/dc/terms/>
            SELECT DISTINCT ?c ?label WHERE {
              GRAPH <%s> {
                ?c a skos:Concept ; skos:prefLabel ?label .
                ?c ?termPredicate ?term .
                VALUES ?termPredicate { skos:prefLabel skos:altLabel skos:definition skos:notation dct:identifier }
                FILTER(CONTAINS(LCASE(STR(?term)), ?needle))
                FILTER(LANG(?label) = "" || LANGMATCHES(LANG(?label), "en"))
              }
            }
            ORDER BY LCASE(STR(?label)) ?c
            LIMIT %d
            """.formatted(ASSERTED, limit);
        var prepared = new ParameterizedSparqlString(sparql);
        prepared.setLiteral("needle", text.trim().toLowerCase(java.util.Locale.ROOT));
        return store.read(dataset -> {
            List<ConceptSummary> results = new ArrayList<>();
            try (var query = QueryExecutionFactory.create(prepared.asQuery(), dataset)) {
                var rs = query.execSelect();
                while (rs.hasNext()) {
                    var row = rs.nextSolution();
                    results.add(summary(row.getResource("c"), row.getLiteral("label")));
                }
            }
            return results;
        });
    }

    @Override public Optional<ConceptSummary> findBySlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9][-a-z0-9]*")) return Optional.empty();
        return findByIri(NS + "concept/" + slug);
    }

    public Optional<ConceptSummary> findByIri(String iri) {
        if (iri == null || !iri.startsWith(NS + "concept/")) return Optional.empty();
        return store.read(dataset -> {
            var model = dataset.getNamedModel(ASSERTED);
            var resource = model.getResource(iri);
            if (!model.contains(resource, RDF.type, SKOS.Concept)) return Optional.empty();
            var properties = resource.listProperties(SKOS.prefLabel);
            Literal label = null;
            while (properties.hasNext()) {
                var candidate = properties.nextStatement().getObject();
                if (candidate.isLiteral()) {
                    label = candidate.asLiteral();
                    if ("en".equalsIgnoreCase(label.getLanguage())) break;
                }
            }
            return label == null ? Optional.empty() : Optional.of(summary(resource, label));
        });
    }
}
