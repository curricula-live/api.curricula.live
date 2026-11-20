package live.curricula.api.rdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Service;

/** Search recorded connections between two canonical concepts. */
@Service
public final class RdfConnectionSearch {
    private static final String BASE = RdfReleaseStore.BASE;
    private static final String ASSERTED_GRAPH = BASE + "graph/asserted";
    private static final Comparator<Literal> LABEL_ORDER = Comparator
            .comparingInt(RdfConnectionSearch::languageRank)
            .thenComparing(Literal::getString);
    private static final String QUERY = """
        PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        PREFIX g: <%sgraph/>
        SELECT ?subject ?predicate ?object ?graph WHERE {
            VALUES ?graph { g:asserted g:inferred g:alignments }
            GRAPH ?graph { ?subject ?predicate ?object }
            FILTER(isIRI(?subject) && isIRI(?object))
            GRAPH g:asserted {
                ?subject a skos:Concept .
                ?object a skos:Concept .
            }
            BIND(REPLACE(LCASE(STR(?subject)), "^.*[/#]", "") AS ?subjectLocal)
            BIND(REPLACE(LCASE(STR(?predicate)), "^.*[/#]", "") AS ?predicateLocal)
            BIND(REPLACE(LCASE(STR(?object)), "^.*[/#]", "") AS ?objectLocal)
            BIND(REPLACE(?subjectLocal, "[-_ ]+", " ") AS ?subjectKey)
            BIND(REPLACE(?predicateLocal, "[-_ ]+", " ") AS ?predicateKey)
            BIND(REPLACE(?objectLocal, "[-_ ]+", " ") AS ?objectKey)
            BIND(?subjectKey = ?needle || ?predicateKey = ?needle || ?objectKey = ?needle
                || EXISTS {
                    GRAPH g:asserted {
                        { ?subject skos:prefLabel|skos:altLabel ?exactLabel }
                        UNION { ?object skos:prefLabel|skos:altLabel ?exactLabel }
                        FILTER(isLiteral(?exactLabel)
                            && REPLACE(LCASE(STR(?exactLabel)), "[-_ ]+", " ") = ?needle)
                    }
                } AS ?exact)
            FILTER(?exact || CONTAINS(?subjectKey, ?needle) || CONTAINS(?predicateKey, ?needle)
                || CONTAINS(?objectKey, ?needle) || EXISTS {
                    GRAPH g:asserted {
                        { ?subject skos:prefLabel|skos:altLabel ?partialLabel }
                        UNION { ?object skos:prefLabel|skos:altLabel ?partialLabel }
                        FILTER(isLiteral(?partialLabel)
                            && CONTAINS(REPLACE(LCASE(STR(?partialLabel)), "[-_ ]+", " "), ?needle))
                    }
                })
        }
        ORDER BY DESC(?exact) ?subject ?predicate ?object ?graph
        LIMIT %d
        """;

    private final RdfReleaseStore store;

    public RdfConnectionSearch(RdfReleaseStore store) {
        this.store = store;
    }

    public record Connection(String subject, String predicate, String object, String graph,
                             String subjectSlug, String subjectLabel, String objectSlug, String objectLabel) { }

    public List<Connection> search(String text, int limit) {
        var prepared = prepare(text, limit);
        return store.read(dataset -> execute(dataset, prepared));
    }

    static ParameterizedSparqlString prepare(String text, int limit) {
        if (text == null || text.length() > 160 || !text.codePoints().anyMatch(Character::isLetterOrDigit)
                || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Provide searchable text of at most 160 characters and a limit of 1..100");
        }
        var prepared = new ParameterizedSparqlString(QUERY.formatted(BASE, limit));
        prepared.setLiteral("needle", text.strip().toLowerCase(Locale.ROOT).replaceAll("[-_ ]+", " ").strip());
        return prepared;
    }

    static List<Connection> execute(Dataset dataset, ParameterizedSparqlString prepared) {
        var asserted = dataset.getNamedModel(ASSERTED_GRAPH);
        var connections = new ArrayList<Connection>();
        try (var execution = QueryExecution.dataset(dataset).query(prepared.asQuery())
                .timeout(4, TimeUnit.SECONDS).build()) {
            var rows = execution.execSelect();
            while (rows.hasNext()) {
                var row = rows.nextSolution();
                var subject = asserted.getResource(row.getResource("subject").getURI());
                var object = asserted.getResource(row.getResource("object").getURI());
                var predicate = row.getResource("predicate").getURI();
                var graph = row.getResource("graph").getURI();
                connections.add(new Connection(subject.getURI(), predicate, object.getURI(),
                        graph.substring((BASE + "graph/").length()), JenaKnowledgeCatalog.slugFromIri(subject.getURI()),
                        preferredLabel(subject), JenaKnowledgeCatalog.slugFromIri(object.getURI()), preferredLabel(object)));
            }
        }
        return List.copyOf(connections);
    }

    private static String preferredLabel(Resource concept) {
        Literal preferred = null;
        var statements = concept.listProperties(SKOS.prefLabel);
        try {
            while (statements.hasNext()) {
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
