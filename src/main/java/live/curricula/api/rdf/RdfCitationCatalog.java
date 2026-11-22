package live.curricula.api.rdf;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.stereotype.Service;

/** Bibliography and citation records from the active release's named provenance graph. */
@Service
public final class RdfCitationCatalog {
    private static final String BASE = RdfReleaseStore.BASE;
    private static final String PREFIXES = """
            PREFIX dct: <http://purl.org/dc/terms/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX prov: <http://www.w3.org/ns/prov#>
            """;
    private static final String GRAPH_PATTERN = """
            GRAPH <%sgraph/provenance> {
              ?assertion a rdf:Statement ; rdf:subject ?subject ; rdf:predicate ?predicate ;
                 rdf:object ?object ; dct:source ?source .
              FILTER(isIRI(?assertion) && isIRI(?subject) && isIRI(?predicate) && (isIRI(?object) || isLiteral(?object)))
              FILTER(isIRI(?source) && REGEX(STR(?source), "^https?://", "i"))
              %s
            }
            """;
    private final RdfQueryStore store;

    public RdfCitationCatalog(RdfQueryStore store) {
        this.store = store;
    }

    public record Page<T>(String version, String manifestSha256, int offset, Integer nextOffset, List<T> results) { }
    public record Source(String iri, long assertions) { }
    public record Term(String value, String type, String datatype, String language) { }
    public record Citation(String assertionIri, String subjectIri, String subjectSlug, String predicateIri,
                           Term object, String objectSlug, String sourceIri, String graphIri) { }
    public static final class PublicationChanged extends RuntimeException { }

    public Page<Source> sources(int offset, int limit, String pin) {
        var query = new ParameterizedSparqlString(PREFIXES + """
                SELECT ?source (COUNT(DISTINCT ?assertion) AS ?count) WHERE {
                %s
                }
                GROUP BY ?source ORDER BY STR(?source)
                """.formatted(GRAPH_PATTERN.formatted(BASE, "")));
        return page(offset, limit, pin, dataset -> rows(dataset, query, offset, limit,
                row -> new Source(row.getResource("source").getURI(), row.getLiteral("count").getLong())));
    }

    public Page<Citation> citations(String source, String conceptSlug, int offset, int limit, String pin) {
        if (source != null) {
            RdfOntologyRegistry.requireAbsoluteIri(source);
            var uri = java.net.URI.create(source);
            if (uri.getHost() == null || !List.of("http", "https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Source must be an HTTP(S) IRI");
            }
        }
        var concept = RdfOntologyRegistry.FORMAL_SCIENCES;
        String conceptIri = conceptSlug == null ? null : concept.conceptIri(conceptSlug);
        String filters = (source == null ? "" : "FILTER(?source = ?sourceFilter)\n")
                + (conceptIri == null ? "" : "FILTER(?subject = ?conceptFilter || ?object = ?conceptFilter)\n");
        var query = new ParameterizedSparqlString(PREFIXES + """
                SELECT DISTINCT ?assertion ?subject ?predicate ?object ?source WHERE {
                %s
                }
                ORDER BY STR(?assertion) STR(?source) STR(?subject) STR(?predicate) ?object
                """.formatted(GRAPH_PATTERN.formatted(BASE, filters)));
        if (source != null) query.setIri("sourceFilter", source);
        if (conceptIri != null) query.setIri("conceptFilter", conceptIri);

        return page(offset, limit, pin, dataset -> {
            if (conceptIri != null && RdfOntologyRegistry.DEFAULT.concept(dataset, conceptIri).isEmpty()) {
                throw new NoSuchElementException();
            }
            return rows(dataset, query, offset, limit, RdfCitationCatalog::citation);
        });
    }

    private <T> Page<T> page(int offset, int limit, String pin, Function<Dataset, List<T>> query) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Offset must be 0..10000 and limit 1..100");
        }
        if (pin != null && !RdfReleaseVerifier.validDigest(pin)) {
            throw new IllegalArgumentException("Publication pin must be a manifest SHA-256");
        }
        return store.readFormal(dataset -> {
            var publication = store.status();
            if (pin != null && !pin.equals(publication.manifestSha256())) throw new PublicationChanged();
            List<T> rows = query.apply(dataset);
            boolean more = rows.size() > limit;
            Integer next = more && offset + limit <= 10000 ? offset + limit : null;
            return new Page<>(publication.version(), publication.manifestSha256(), offset, next,
                    more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows));
        });
    }

    private static <T> List<T> rows(Dataset dataset, ParameterizedSparqlString query, int offset, int limit,
                                    Function<QuerySolution, T> mapper) {
        var bounded = query.asQuery();
        bounded.setOffset(offset);
        bounded.setLimit(limit + 1L);
        var rows = new ArrayList<T>();
        try (var execution = QueryExecution.dataset(dataset).query(bounded).timeout(5, TimeUnit.SECONDS).build()) {
            var results = execution.execSelect();
            while (results.hasNext()) rows.add(mapper.apply(results.nextSolution()));
        }
        return rows;
    }

    private static Citation citation(QuerySolution row) {
        String subject = row.getResource("subject").getURI();
        RDFNode object = row.get("object");
        var term = object.isURIResource() ? new Term(object.asResource().getURI(), "iri", null, null)
                : new Term(object.asLiteral().getLexicalForm(), "literal", object.asLiteral().getDatatypeURI(),
                object.asLiteral().getLanguage());
        return new Citation(row.getResource("assertion").getURI(), subject, slug(subject),
                row.getResource("predicate").getURI(), term, object.isURIResource() ? slug(term.value()) : null,
                row.getResource("source").getURI(), BASE + "graph/provenance");
    }

    private static String slug(String iri) {
        return RdfOntologyRegistry.FORMAL_SCIENCES.ownsConcept(iri) ? JenaKnowledgeCatalog.slugFromIri(iri) : null;
    }
}
