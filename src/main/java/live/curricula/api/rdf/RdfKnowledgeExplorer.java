package live.curricula.api.rdf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;
import live.curricula.api.rdf.RdfOntologyRegistry.Producer;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

/** Traverses recorded named-graph statements without manufacturing inferred facts. */
public final class RdfKnowledgeExplorer {
    private static final List<String> GRAPH_LAYERS = List.of("asserted", "inferred", "alignments");
    private static final Set<String> KINDS = Set.of("all", "taxonomy", "mapping", "association", "scientific", "other");
    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 1000;
    private static final int MAX_RELATIONSHIP_OFFSET = 10_000;
    private static final Property SOURCE = ResourceFactory.createProperty("http://purl.org/dc/terms/source");
    private static final Property WITNESS = ResourceFactory.createProperty("http://www.w3.org/ns/prov#wasDerivedFrom");
    private static final Property IDENTIFIER = ResourceFactory.createProperty("http://purl.org/dc/terms/identifier");
    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing((Edge edge) -> edge.key().layer())
            .thenComparing(edge -> edge.key().predicate())
            .thenComparing(edge -> edge.key().subject())
            .thenComparing(edge -> edge.key().object())
            .thenComparing(edge -> edge.key().graphIri());
    private static final Comparator<Evidence> EVIDENCE_ORDER = Comparator.comparing(Evidence::assertionIri)
            .thenComparing(Evidence::graphIri);

    private final RdfReleaseStore store;
    private final RdfOntologyRegistry ontologies;

    public RdfKnowledgeExplorer(RdfReleaseStore store) {
        this(store, RdfOntologyRegistry.DEFAULT);
    }

    public RdfKnowledgeExplorer(RdfReleaseStore store, RdfOntologyRegistry ontologies) {
        this.store = store;
        this.ontologies = ontologies;
    }

    public record Relationship(String subject, String predicate, String object, String graph, String direction,
                               String kind, String objectLabel, String graphIri) { }
    public record Evidence(String assertionIri, String predicate, String object, String source,
                           String witness, String identifier, String subject, String graphIri) { }
    public record NodeDetail(String iri, String label, String slug, List<String> domains) { }
    public record Neighborhood(String root, List<String> nodes, List<Relationship> edges,
                               int depth, boolean truncated, List<NodeDetail> nodeDetails) { }

    public record RelationshipFilter(String graph, String kind, String predicate, String domain) {
        public RelationshipFilter {
            if (!"all".equals(graph) && !GRAPH_LAYERS.contains(graph)) {
                throw new IllegalArgumentException("Invalid named-graph selection");
            }
            if (kind == null || !KINDS.contains(kind)) {
                throw new IllegalArgumentException("Invalid relationship category");
            }
            if (predicate != null) RdfOntologyRegistry.requireAbsoluteIri(predicate);
            if (domain != null && !isDomainSlug(domain)) RdfOntologyRegistry.requireAbsoluteIri(domain);
        }
    }

    private record EdgeKey(String subject, String predicate, String object, String layer, String graphIri) { }
    private record Edge(EdgeKey key, String neighbor, String direction, String kind) { }
    private record Visit(String iri, int hop) { }

    private String kind(String predicate) {
        if (predicate.equals(SKOS.broader.getURI()) || predicate.equals(SKOS.narrower.getURI())) return "taxonomy";
        if (predicate.equals(SKOS.exactMatch.getURI()) || predicate.equals(SKOS.closeMatch.getURI())
                || predicate.equals(RDFS.seeAlso.getURI())) return "mapping";
        if (ontologies.producers().stream().anyMatch(producer -> producer.ownsPredicate(predicate))) return "scientific";
        if (predicate.equals(SKOS.related.getURI())) return "association";
        return "other";
    }

    public List<Relationship> relationships(String iri, String layer, int limit) {
        return relationships(iri, new RelationshipFilter(layer, "all", null, null), 0, limit);
    }

    public List<Relationship> relationships(String iri, RelationshipFilter filter, int offset, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit outside 1..500");
        if (offset < 0 || offset > MAX_RELATIONSHIP_OFFSET) {
            throw new IllegalArgumentException("offset outside 0..10000");
        }
        return withConcept(iri, dataset -> collect(dataset, iri, filter, offset + limit).stream()
                .skip(offset).map(edge -> describe(dataset, edge)).toList());
    }

    // TDB2 looks up statements indexed by focus or by object. Keep only the requested
    // ordered prefix while scanning those adjacencies, regardless of node degree.
    private List<Edge> collect(Dataset dataset, String iri, RelationshipFilter filter, int count) {
        var selected = new Smallest<>(EDGE_ORDER, count);
        for (Producer producer : ontologies.producers()) {
            for (String layer : GRAPH_LAYERS) {
                if (!"all".equals(filter.graph()) && !layer.equals(filter.graph())) continue;
                String graphIri = producer.graphIri(layer);
                Model model = dataset.getNamedModel(graphIri);
                Resource focus = model.getResource(iri);
                Property predicate = filter.predicate() == null ? null : model.getProperty(filter.predicate());
                var outgoing = model.listStatements(focus, predicate, (RDFNode) null);
                try {
                    while (outgoing.hasNext()) {
                        Statement statement = outgoing.nextStatement();
                        if (statement.getObject().isURIResource()) {
                            keepEdge(dataset, selected, filter, statement, layer, graphIri,
                                    statement.getResource().getURI(), "outgoing");
                        }
                    }
                } finally {
                    outgoing.close();
                }
                var incoming = model.listStatements(null, predicate, focus);
                try {
                    while (incoming.hasNext()) {
                        Statement statement = incoming.nextStatement();
                        if (statement.getSubject().isURIResource() && !statement.getSubject().equals(focus)) {
                            keepEdge(dataset, selected, filter, statement, layer, graphIri,
                                    statement.getSubject().getURI(), "incoming");
                        }
                    }
                } finally {
                    incoming.close();
                }
            }
        }
        return selected.sorted();
    }

    private void keepEdge(Dataset dataset, Smallest<Edge> selected, RelationshipFilter filter, Statement statement,
                          String layer, String graphIri, String neighbor, String direction) {
        String predicate = statement.getPredicate().getURI();
        String kind = kind(predicate);
        if (!"all".equals(filter.kind()) && !kind.equals(filter.kind())) return;
        if (!layer.equals("alignments") || filter.domain() != null) {
            var concept = ontologies.concept(dataset, neighbor);
            if (concept.isEmpty()) return;
            if (filter.domain() != null && !matchesDomain(concept.get(), filter.domain())) return;
        }
        selected.add(new Edge(new EdgeKey(statement.getSubject().getURI(), predicate,
                statement.getResource().getURI(), layer, graphIri), neighbor, direction, kind));
    }

    private boolean matchesDomain(Resource concept, String domain) {
        String scheme = isDomainSlug(domain)
                ? ontologies.ownerOf(concept.getURI()).orElseThrow().schemeIri(domain)
                : domain;
        return concept.hasProperty(SKOS.inScheme, concept.getModel().getResource(scheme));
    }

    private static boolean isDomainSlug(String domain) {
        return domain.length() <= 128 && domain.matches("[a-z][-a-z0-9]*");
    }

    private Relationship describe(Dataset dataset, Edge edge) {
        EdgeKey key = edge.key();
        String label = ontologies.concept(dataset, edge.neighbor())
                .flatMap(RdfOntologyRegistry::preferredLabel).orElse(edge.neighbor());
        return new Relationship(key.subject(), key.predicate(), key.object(), key.layer(),
                edge.direction(), edge.kind(), label, key.graphIri());
    }

    public Neighborhood neighborhood(String iri, int depth) {
        return neighborhood(iri, depth, new RelationshipFilter("all", "all", null, null), MAX_EDGES);
    }

    public Neighborhood neighborhood(String iri, int depth, RelationshipFilter filter, int limit) {
        if (depth < 1 || depth > 5) throw new IllegalArgumentException("depth outside 1..5");
        if (limit < 1 || limit > MAX_EDGES) throw new IllegalArgumentException("limit outside 1..1000");
        return withConcept(iri, dataset -> {
            var nodes = new LinkedHashSet<>(List.of(iri));
            var edges = new LinkedHashMap<EdgeKey, Edge>();
            var frontier = new ArrayDeque<Visit>();
            frontier.add(new Visit(iri, 0));
            boolean truncated = false;
            while (!frontier.isEmpty() && edges.size() < limit) {
                Visit focus = frontier.removeFirst();
                // If an adjacency exceeds the full response budget, its unseen
                // suffix cannot fit even if the entire prefix was already visited.
                var adjacency = collect(dataset, focus.iri(), filter, limit + 1);
                if (adjacency.size() > limit) truncated = true;
                for (Edge edge : adjacency) {
                    if (edges.containsKey(edge.key())) continue;
                    if (edges.size() == limit) {
                        truncated = true;
                        break;
                    }
                    if (!nodes.contains(edge.neighbor()) && nodes.size() == MAX_NODES) {
                        truncated = true;
                        continue;
                    }
                    edges.put(edge.key(), edge);
                    if (nodes.add(edge.neighbor()) && focus.hop() + 1 < depth
                            && ontologies.concept(dataset, edge.neighbor()).isPresent()) {
                        frontier.add(new Visit(edge.neighbor(), focus.hop() + 1));
                    }
                }
            }
            // A boundary exactly at the budget is complete if remaining visits
            // contribute only statements already returned.
            while (!truncated && !frontier.isEmpty()) {
                truncated = collect(dataset, frontier.removeFirst().iri(), filter, limit + 1).stream()
                        .anyMatch(edge -> !edges.containsKey(edge.key()));
            }
            var details = nodes.stream().flatMap(node -> ontologies.concept(dataset, node).stream())
                    .map(this::nodeDetail).toList();
            return new Neighborhood(iri, List.copyOf(nodes), edges.values().stream()
                    .map(edge -> describe(dataset, edge)).toList(), depth, truncated, details);
        });
    }

    private NodeDetail nodeDetail(Resource node) {
        var domains = new ArrayList<String>();
        var statements = node.listProperties(SKOS.inScheme);
        try {
            while (statements.hasNext()) {
                RDFNode scheme = statements.nextStatement().getObject();
                if (scheme.isURIResource()) domains.add(scheme.asResource().getURI());
            }
        } finally {
            statements.close();
        }
        var formalSciences = RdfOntologyRegistry.FORMAL_SCIENCES;
        String iri = node.getURI();
        return new NodeDetail(iri, RdfOntologyRegistry.preferredLabel(node).orElse(iri),
                formalSciences.ownsConcept(iri) ? formalSciences.localName(iri) : null,
                domains.stream().sorted().toList());
    }

    public List<Evidence> provenance(String iri, int limit) {
        return provenance(iri, limit, "outgoing", null, null);
    }

    public List<Evidence> provenance(String iri, int limit, String direction, String predicate, String adjacent) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit outside 1..200");
        if (!List.of("outgoing", "incoming", "all").contains(direction)) {
            throw new IllegalArgumentException("Invalid evidence direction");
        }
        if (predicate != null) RdfOntologyRegistry.requireAbsoluteIri(predicate);
        if (adjacent != null) RdfOntologyRegistry.requireAbsoluteIri(adjacent);
        return withConcept(iri, dataset -> {
            var selected = new Smallest<>(EVIDENCE_ORDER, limit);
            for (Producer producer : ontologies.producers()) {
                String graph = producer.graphIri("provenance");
                Model model = dataset.getNamedModel(graph);
                if (!"incoming".equals(direction)) {
                    collectEvidence(model, graph, iri, RDF.subject, RDF.object, predicate, adjacent, false, selected);
                }
                if (!"outgoing".equals(direction)) {
                    collectEvidence(model, graph, iri, RDF.object, RDF.subject, predicate, adjacent,
                            "all".equals(direction), selected);
                }
            }
            return selected.sorted();
        });
    }

    private static void collectEvidence(Model model, String graph, String iri, Property focusProperty,
                                        Property adjacentProperty, String predicate, String adjacent,
                                        boolean skipSelf, Smallest<Evidence> result) {
        var assertions = model.listResourcesWithProperty(focusProperty, model.getResource(iri));
        try {
            while (assertions.hasNext()) {
                Resource assertion = assertions.nextResource();
                if (!assertion.isURIResource()) continue;
                if (skipSelf && assertion.hasProperty(RDF.subject, model.getResource(iri))) continue;
                if (predicate != null && !assertion.hasProperty(RDF.predicate, model.getResource(predicate))) continue;
                if (adjacent != null && !assertion.hasProperty(adjacentProperty, model.getResource(adjacent))) continue;
                result.add(new Evidence(assertion.getURI(), value(assertion, RDF.predicate), value(assertion, RDF.object),
                        value(assertion, SOURCE), value(assertion, WITNESS), value(assertion, IDENTIFIER),
                        value(assertion, RDF.subject), graph));
            }
        } finally {
            assertions.close();
        }
    }

    private static String value(Resource subject, Property predicate) {
        Statement statement = subject.getProperty(predicate);
        if (statement == null) return null;
        var object = statement.getObject();
        return object.isURIResource() ? object.asResource().getURI()
                : object.isLiteral() ? object.asLiteral().getString() : null;
    }

    private <T> T withConcept(String iri, java.util.function.Function<Dataset, T> query) {
        RdfOntologyRegistry.requireAbsoluteIri(iri);
        if (ontologies.ownerOf(iri).isEmpty()) throw new NoSuchElementException("Canonical concept not found");
        return store.read(dataset -> {
            if (ontologies.concept(dataset, iri).isEmpty()) {
                throw new NoSuchElementException("Canonical concept not found");
            }
            return query.apply(dataset);
        });
    }

    /** Bounded ordered selection without materializing all incident statements. */
    private static final class Smallest<T> {
        private final Comparator<T> order;
        private final PriorityQueue<T> values;
        private final int capacity;

        Smallest(Comparator<T> order, int capacity) {
            this.order = order;
            this.capacity = capacity;
            values = new PriorityQueue<>(order.reversed());
        }

        void add(T candidate) {
            if (values.size() == capacity) {
                if (order.compare(candidate, values.element()) >= 0) return;
                values.remove();
            }
            values.add(candidate);
        }

        List<T> sorted() {
            return values.stream().sorted(order).toList();
        }
    }
}
