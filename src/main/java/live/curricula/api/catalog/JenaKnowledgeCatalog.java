package live.curricula.api.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import live.curricula.api.rdf.RdfOntologyRegistry;
import live.curricula.api.rdf.RdfReleaseStore;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

/** Read-only RDF-native query adapter. Never relies on default-graph unions. */
public final class JenaKnowledgeCatalog implements KnowledgeCatalog {
    private static final String NS = RdfReleaseStore.BASE;
    private static final String ASSERTED = NS + "graph/asserted";
    private static final List<Property> SEARCH_PROPERTIES =
            List.of(SKOS.prefLabel, SKOS.altLabel, SKOS.definition, SKOS.notation, DCTerms.identifier);
    private static final Comparator<ConceptSummary> SEARCH_ORDER = Comparator.comparing(
            (ConceptSummary concept) -> concept.label().toLowerCase(Locale.ROOT))
            .thenComparing(ConceptSummary::iri)
            .thenComparing(ConceptSummary::label);
    private final RdfReleaseStore store;
    private final RdfOntologyRegistry ontologies;

    public JenaKnowledgeCatalog(RdfReleaseStore store) {
        this(store, RdfOntologyRegistry.DEFAULT);
    }

    public JenaKnowledgeCatalog(RdfReleaseStore store, RdfOntologyRegistry ontologies) {
        this.store = store;
        this.ontologies = ontologies;
    }

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
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return store.read(dataset -> {
            Model asserted = dataset.getNamedModel(ASSERTED);
            var best = new PriorityQueue<>(limit, SEARCH_ORDER.reversed());
            for (Resource subject : matchingSubjects(asserted, needle)) {
                if (!asserted.contains(subject, RDF.type, SKOS.Concept)) continue;
                var labels = subject.listProperties(SKOS.prefLabel);
                try {
                    while (labels.hasNext()) {
                        if (!(labels.nextStatement().getObject() instanceof Literal label)
                                || !displayLanguage(label)) continue;
                        ConceptSummary candidate = summary(subject, label);
                        if (best.size() < limit) {
                            best.add(candidate);
                        } else if (SEARCH_ORDER.compare(candidate, best.element()) < 0) {
                            best.remove();
                            best.add(candidate);
                        }
                    }
                } finally {
                    labels.close();
                }
            }
            return best.stream().sorted(SEARCH_ORDER).toList();
        });
    }

    /** Traverse each predicate's TDB2 range once, then resolve labels for matching subjects. */
    private static Set<Resource> matchingSubjects(Model asserted, String needle) {
        Set<Resource> matches = new HashSet<>();
        for (Property property : SEARCH_PROPERTIES) {
            var terms = asserted.listStatements(null, property, (RDFNode) null);
            try {
                while (terms.hasNext()) {
                    var statement = terms.nextStatement();
                    if (statement.getSubject().isURIResource() && contains(statement.getObject(), needle)) {
                        matches.add(statement.getSubject());
                    }
                }
            } finally {
                terms.close();
            }
        }
        return matches;
    }

    private static boolean contains(RDFNode term, String needle) {
        return switch (term) {
            case Literal literal -> literal.getLexicalForm().toLowerCase(Locale.ROOT).contains(needle);
            case Resource resource when resource.isURIResource() ->
                    resource.getURI().toLowerCase(Locale.ROOT).contains(needle);
            default -> false;
        };
    }

    private static boolean displayLanguage(Literal label) {
        String language = label.getLanguage().toLowerCase(Locale.ROOT);
        return language.isEmpty() || language.equals("en") || language.startsWith("en-");
    }

    @Override public Optional<ConceptSummary> findBySlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9][-a-z0-9]*")) return Optional.empty();
        return findByIri(NS + "concept/" + slug);
    }

    public Optional<ConceptSummary> findByIri(String iri) {
        var producer = ontologies.ownerOf(iri);
        if (producer.isEmpty()) return Optional.empty();
        return store.read(dataset -> ontologies.concept(dataset, iri)
                .flatMap(concept -> RdfOntologyRegistry.preferredLabel(concept)
                        .map(label -> new ConceptSummary(iri, producer.get().localName(iri), label))));
    }
}
