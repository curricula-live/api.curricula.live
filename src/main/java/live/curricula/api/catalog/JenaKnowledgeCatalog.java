package live.curricula.api.catalog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[-_\\s]+");
    private static final Comparator<Ranked> RANK_ORDER = Comparator.comparingInt(Ranked::rank).reversed()
            .thenComparing(Ranked::concept, SEARCH_ORDER);
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

    private record Ranked(ConceptSummary concept, int rank) { }

    @Override public List<ConceptSummary> search(String text, int limit) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 160
                || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Provide searchable text of at most 160 characters and a limit of 1..100");
        }
        String needle = normalize(text);
        if (needle.isBlank()) return List.of();
        return store.read(dataset -> {
            Model asserted = dataset.getNamedModel(ASSERTED);
            var best = new PriorityQueue<>(limit, RANK_ORDER.reversed());
            for (var match : matchingSubjects(asserted, needle).entrySet()) {
                Resource subject = match.getKey();
                if (!asserted.contains(subject, RDF.type, SKOS.Concept)) continue;
                var labels = subject.listProperties(SKOS.prefLabel);
                ConceptSummary selected = null;
                try {
                    while (labels.hasNext()) {
                        if (!(labels.nextStatement().getObject() instanceof Literal label)
                                || !displayLanguage(label)) continue;
                        var candidate = summary(subject, label);
                        if (selected == null || SEARCH_ORDER.compare(candidate, selected) < 0) selected = candidate;
                    }
                } finally {
                    labels.close();
                }
                if (selected == null) continue;
                var candidate = new Ranked(selected, match.getValue());
                if (best.size() < limit) best.add(candidate);
                else if (RANK_ORDER.compare(candidate, best.element()) < 0) {
                    best.remove();
                    best.add(candidate);
                }
            }
            return best.stream().sorted(RANK_ORDER).map(Ranked::concept).toList();
        });
    }

    /** Use each searchable predicate's TDB2 index and keep one relevance score per matching concept. */
    private static Map<Resource, Integer> matchingSubjects(Model asserted, String needle) {
        var matches = new HashMap<Resource, Integer>();
        var concepts = asserted.listResourcesWithProperty(RDF.type, SKOS.Concept);
        try {
            while (concepts.hasNext()) {
                Resource concept = concepts.next();
                if (concept.isURIResource()) recordRank(matches, concept, rank(slugFromIri(concept.getURI()), needle, 2));
            }
        } finally {
            concepts.close();
        }
        for (Property property : SEARCH_PROPERTIES) {
            int labelPriority = property.equals(SKOS.prefLabel) ? 2 : property.equals(SKOS.altLabel) ? 1 : 0;
            var terms = asserted.listStatements(null, property, (RDFNode) null);
            try {
                while (terms.hasNext()) {
                    var statement = terms.nextStatement();
                    if (!statement.getSubject().isURIResource()) continue;
                    String value = switch (statement.getObject()) {
                        case Literal literal -> literal.getLexicalForm();
                        case Resource resource when resource.isURIResource() -> resource.getURI();
                        default -> "";
                    };
                    recordRank(matches, statement.getSubject(), rank(value, needle, labelPriority));
                }
            } finally {
                terms.close();
            }
        }
        return matches;
    }

    private static void recordRank(Map<Resource, Integer> matches, Resource concept, int rank) {
        if (rank > 0) matches.merge(concept, rank, Math::max);
    }

    private static int rank(String value, String needle, int labelPriority) {
        String normalized = normalize(value);
        if (!normalized.contains(needle)) return 0;
        if (labelPriority == 0) return 1;
        if (normalized.equals(needle)) return 3 + labelPriority;
        return normalized.startsWith(needle) ? 3 : 2;
    }

    private static String normalize(String text) {
        String folded = text.toLowerCase(Locale.ROOT);
        if (folded.chars().anyMatch(character -> character > 127)) {
            folded = MARKS.matcher(Normalizer.normalize(folded, Normalizer.Form.NFD)).replaceAll("");
        }
        return SEPARATORS.matcher(folded).replaceAll(" ").strip();
    }

    /** The v1 wire format lists canonical formal-sciences slugs without projecting RDF terms. */
    public List<String> canonicalSlugs() {
        return store.read(dataset -> {
            var slugs = new TreeSet<String>();
            var concepts = dataset.getNamedModel(ASSERTED).listResourcesWithProperty(RDF.type, SKOS.Concept);
            try {
                while (concepts.hasNext()) {
                    Resource concept = concepts.next();
                    if (concept.isURIResource() && RdfOntologyRegistry.FORMAL_SCIENCES.ownsConcept(concept.getURI())) {
                        slugs.add(slugFromIri(concept.getURI()));
                    }
                }
            } finally {
                concepts.close();
            }
            return List.copyOf(slugs);
        });
    }

    private static boolean displayLanguage(Literal label) {
        String language = label.getLanguage().toLowerCase(Locale.ROOT);
        return language.isEmpty() || language.equals("en") || language.startsWith("en-");
    }

    public record Definition(String text, String language) { }
    public record Documentation(String iri, List<Definition> definitions) { }

    public Optional<Documentation> documentation(String iri) {
        if (ontologies.ownerOf(iri).isEmpty()) return Optional.empty();
        return store.read(dataset -> ontologies.concept(dataset, iri).map(concept -> {
            var definitions = new TreeSet<>(Comparator.comparing(Definition::language).thenComparing(Definition::text));
            var terms = concept.listProperties(SKOS.definition);
            try {
                while (terms.hasNext()) {
                    if (terms.next().getObject() instanceof Literal literal) {
                        definitions.add(new Definition(literal.getString(), literal.getLanguage()));
                        if (definitions.size() > 20) definitions.pollLast();
                    }
                }
            } finally {
                terms.close();
            }
            return new Documentation(iri, List.copyOf(definitions));
        }));
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
