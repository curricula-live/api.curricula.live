package live.curricula.api.rdf;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Pattern;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Service;

/** Search recorded connections between two canonical concepts. */
@Service
public final class RdfConnectionSearch {
    private static final String BASE = RdfReleaseStore.BASE;
    private static final String GRAPH_BASE = BASE + "graph/";
    private static final String CONCEPT_BASE = BASE + "concept/";
    private static final List<String> LAYERS = List.of("asserted", "inferred", "alignments");
    private static final Pattern WORD_SEPARATORS = Pattern.compile("[-_ ]+");
    private static final long TIME_BUDGET = Duration.ofSeconds(4).toNanos();
    private static final int MATCH_CACHE_LIMIT = 4096;
    private static final int NOT_CANONICAL = -1;
    private static final int NO_MATCH = 0;
    private static final int EXACT_MATCH = 2;
    private static final Comparator<Literal> LABEL_ORDER = Comparator
            .comparingInt(RdfConnectionSearch::languageRank)
            .thenComparing(Literal::getString);
    private static final Comparator<Candidate> MATCH_ORDER = Comparator
            .comparingInt(Candidate::rank).reversed()
            .thenComparing(Candidate::subject)
            .thenComparing(Candidate::predicate)
            .thenComparing(Candidate::object)
            .thenComparing(Candidate::layer);

    private final RdfQueryStore store;

    public RdfConnectionSearch(RdfQueryStore store) {
        this.store = store;
    }

    public record Connection(String subject, String predicate, String object, String graph,
                             String subjectSlug, String subjectLabel, String objectSlug, String objectLabel) { }

    private record Candidate(String subject, String predicate, String object, String layer, int rank) { }

    record SearchTerm(String text, int limit) { }

    public static final class SearchTimeoutException extends RuntimeException { }

    public List<Connection> search(String text, int limit) {
        var term = prepare(text, limit);
        return store.readFormal(dataset -> execute(dataset, term));
    }

    static SearchTerm prepare(String text, int limit) {
        if (text == null || text.codePointCount(0, text.length()) > 160
                || !text.codePoints().anyMatch(Character::isLetterOrDigit) || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Provide searchable text of at most 160 characters and a limit of 1..100");
        }
        return new SearchTerm(normalize(text).strip(), limit);
    }

    static List<Connection> execute(Dataset dataset, SearchTerm term) {
        long deadline = System.nanoTime() + TIME_BUDGET;
        var assertedModel = dataset.getNamedModel(GRAPH_BASE + "asserted");
        var asserted = assertedModel.getGraph();
        var candidates = new PriorityQueue<>(term.limit(), MATCH_ORDER.reversed());
        var endpointRanks = new HashMap<Node, Integer>();
        var predicateRanks = new HashMap<Node, Integer>();

        for (String layer : LAYERS) {
            var graph = dataset.asDatasetGraph().getGraph(NodeFactory.createURI(GRAPH_BASE + layer));
            var statements = graph.find();
            try {
                while (statements.hasNext()) {
                    checkDeadline(deadline);
                    var statement = statements.next();
                    Node subject = statement.getSubject();
                    Node object = statement.getObject();
                    if (!isCanonicalIri(subject) || !isCanonicalIri(object)) continue;
                    int subjectRank = endpointRank(asserted, endpointRanks, subject, term.text(), deadline);
                    if (subjectRank == NOT_CANONICAL) continue;
                    int objectRank = endpointRank(asserted, endpointRanks, object, term.text(), deadline);
                    if (objectRank == NOT_CANONICAL) continue;

                    Node predicate = statement.getPredicate();
                    Integer predicateRank = predicateRanks.get(predicate);
                    if (predicateRank == null) {
                        predicateRank = rank(localName(predicate.getURI()), term.text());
                        remember(predicateRanks, predicate, predicateRank);
                    }
                    int rank = Math.max(predicateRank, Math.max(subjectRank, objectRank));
                    if (rank == NO_MATCH) continue;

                    var candidate = new Candidate(subject.getURI(), predicate.getURI(), object.getURI(), layer, rank);
                    if (candidates.size() < term.limit()) {
                        candidates.add(candidate);
                    } else if (MATCH_ORDER.compare(candidate, candidates.element()) < 0) {
                        candidates.remove();
                        candidates.add(candidate);
                    }
                }
            } finally {
                statements.close();
            }
        }

        checkDeadline(deadline);
        return candidates.stream().sorted(MATCH_ORDER).map(candidate -> {
            checkDeadline(deadline);
            Resource subject = assertedModel.getResource(candidate.subject());
            Resource object = assertedModel.getResource(candidate.object());
            return new Connection(candidate.subject(), candidate.predicate(), candidate.object(), candidate.layer(),
                    JenaKnowledgeCatalog.slugFromIri(candidate.subject()), preferredLabel(subject, deadline),
                    JenaKnowledgeCatalog.slugFromIri(candidate.object()), preferredLabel(object, deadline));
        }).toList();
    }

    private static boolean isCanonicalIri(Node node) {
        return node.isURI() && node.getURI().startsWith(CONCEPT_BASE);
    }

    private static int endpointRank(Graph asserted, Map<Node, Integer> ranks, Node concept, String needle, long deadline) {
        Integer cached = ranks.get(concept);
        if (cached != null) return cached;

        int rank = NOT_CANONICAL;
        if (asserted.contains(concept, RDF.type.asNode(), SKOS.Concept.asNode())) {
            rank = rank(localName(concept.getURI()), needle);
            if (rank < EXACT_MATCH) {
                rank = Math.max(rank, labelRank(asserted, concept, SKOS.prefLabel.asNode(), needle, deadline));
            }
            if (rank < EXACT_MATCH) {
                rank = Math.max(rank, labelRank(asserted, concept, SKOS.altLabel.asNode(), needle, deadline));
            }
        }
        remember(ranks, concept, rank);
        return rank;
    }

    private static void remember(Map<Node, Integer> ranks, Node node, int rank) {
        if (ranks.size() < MATCH_CACHE_LIMIT) ranks.put(node, rank);
    }

    private static int labelRank(Graph asserted, Node concept, Node predicate, String needle, long deadline) {
        int rank = NO_MATCH;
        var statements = asserted.find(concept, predicate, Node.ANY);
        try {
            while (statements.hasNext()) {
                checkDeadline(deadline);
                Node label = statements.next().getObject();
                if (!label.isLiteral()) continue;
                rank = Math.max(rank, rank(label.getLiteralLexicalForm(), needle));
                if (rank == EXACT_MATCH) break;
            }
        } finally {
            statements.close();
        }
        return rank;
    }

    private static int rank(String text, String needle) {
        String normalized = normalize(text);
        if (normalized.equals(needle)) return EXACT_MATCH;
        return normalized.contains(needle) ? 1 : NO_MATCH;
    }

    private static String normalize(String text) {
        return WORD_SEPARATORS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private static String localName(String iri) {
        return iri.substring(Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#')) + 1);
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new SearchTimeoutException();
    }

    private static String preferredLabel(Resource concept, long deadline) {
        Literal preferred = null;
        var statements = concept.listProperties(SKOS.prefLabel);
        try {
            while (statements.hasNext()) {
                checkDeadline(deadline);
                var term = statements.nextStatement().getObject();
                if (term.isLiteral() && (preferred == null || LABEL_ORDER.compare(term.asLiteral(), preferred) < 0)) {
                    preferred = term.asLiteral();
                }
            }
        } finally {
            statements.close();
        }
        return preferred == null ? JenaKnowledgeCatalog.slugFromIri(concept.getURI()) : preferred.getString();
    }

    private static int languageRank(Literal label) {
        String language = label.getLanguage().toLowerCase(Locale.ROOT);
        if (language.equals("en")) return 0;
        if (language.startsWith("en-")) return 1;
        return language.isEmpty() ? 2 : 3;
    }
}
