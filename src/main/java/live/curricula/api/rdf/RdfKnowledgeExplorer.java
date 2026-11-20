package live.curricula.api.rdf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

/** Traverses recorded named-graph statements, without manufacturing inferred facts. */
public final class RdfKnowledgeExplorer {
    private final RdfReleaseStore store;
    private static final String BASE = RdfReleaseStore.BASE;

    public RdfKnowledgeExplorer(RdfReleaseStore store) { this.store = store; }

    public record Relationship(String subject, String predicate, String object,
                               String graph, String direction, String kind, String objectLabel) { }
    public record Evidence(String assertionIri, String predicate, String object,
                           String source, String witness, String identifier) { }
    public record Neighborhood(String root, List<String> nodes, List<Relationship> edges,
                               int depth, boolean truncated) { }

    private static String kind(String predicate) {
        if (predicate.equals(SKOS.broader.getURI()) || predicate.equals(SKOS.narrower.getURI())) return "taxonomy";
        if (predicate.equals(SKOS.exactMatch.getURI()) ||
            predicate.equals(SKOS.closeMatch.getURI()) ||
            predicate.equals("http://www.w3.org/2000/01/rdf-schema#seeAlso")) return "mapping";
        if (predicate.startsWith(BASE + "vocab/")) return "scientific";
        if (predicate.equals(SKOS.related.getURI())) return "association";
        return "other";
    }

    public List<Relationship> relationships(String iri, String layer, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit outside 1..500");
        if (!iri.startsWith(BASE + "concept/")) throw new IllegalArgumentException("Unrecognized concept IRI");
        if (!List.of("all", "asserted", "inferred", "alignments").contains(layer)) {
            throw new IllegalArgumentException("Invalid named-graph selection");
        }
        return store.read(ds -> collect(ds, iri, layer, limit));
    }

    private List<Relationship> collect(org.apache.jena.query.Dataset ds, String iri, String layer, int limit) {
        var result = new LinkedHashSet<Relationship>();
        Model asserted = ds.getNamedModel(BASE + "graph/asserted");
        for (String graphName : List.of("asserted", "inferred", "alignments")) {
            if (!"all".equals(layer) && !graphName.equals(layer)) continue;
            Model model = ds.getNamedModel(BASE + "graph/" + graphName);
            Resource focus = model.getResource(iri);
            var outgoing = model.listStatements(focus, null, (RDFNode)null);
            while (outgoing.hasNext() && result.size() < limit) {
                Statement s = outgoing.nextStatement();
                if (s.getObject().isResource()) {
                    Resource target = s.getResource();
                    if (graphName.equals("alignments") || asserted.contains(target, RDF.type, SKOS.Concept)) {
                        result.add(new Relationship(s.getSubject().getURI(), s.getPredicate().getURI(),
                                target.getURI(), graphName, "outgoing", kind(s.getPredicate().getURI()),
                                label(asserted, target)));
                    }
                }
            }
            var incoming = model.listStatements(null, null, focus);
            while (incoming.hasNext() && result.size() < limit) {
                Statement s = incoming.nextStatement();
                if (graphName.equals("alignments") || asserted.contains(s.getSubject(), RDF.type, SKOS.Concept)) {
                    result.add(new Relationship(s.getSubject().getURI(), s.getPredicate().getURI(),
                            iri, graphName, "incoming", kind(s.getPredicate().getURI()),
                            label(asserted, s.getSubject())));
                }
            }
            if (result.size() >= limit) break;
        }
        return result.stream().sorted(Comparator.comparing(Relationship::graph)
                .thenComparing(Relationship::predicate)
                .thenComparing(Relationship::subject)
                .thenComparing(Relationship::object)).limit(limit).toList();
    }

    private static String label(Model asserted, Resource resource) {
        var labels = asserted.listStatements(resource, SKOS.prefLabel, (RDFNode)null);
        String candidate = resource.getURI();
        while (labels.hasNext()) {
            var object = labels.nextStatement().getObject();
            if (object.isLiteral()) {
                candidate = object.asLiteral().getString();
                if ("en".equalsIgnoreCase(object.asLiteral().getLanguage())) break;
            }
        }
        return candidate;
    }

    public Neighborhood neighborhood(String iri, int depth) {
        if (depth < 1 || depth > 5) throw new IllegalArgumentException("depth outside 1..5");
        if (!iri.startsWith(BASE + "concept/")) throw new IllegalArgumentException("Unrecognized concept IRI");
        return store.read(ds -> {
            Set<String> nodes = new LinkedHashSet<>(List.of(iri));
            Set<Relationship> edges = new LinkedHashSet<>();
            Set<String> visited = new HashSet<>();
            ArrayDeque<String> frontier = new ArrayDeque<>();
            frontier.add(iri);
            boolean truncated = false;
            for (int hop = 0; hop < depth && !frontier.isEmpty(); hop++) {
                int width = frontier.size();
                for (int j = 0; j < width; j++) {
                    String focus = frontier.removeFirst();
                    if (!visited.add(focus)) continue;
                    for (Relationship edge : collect(ds, focus, "all", 500)) {
                        if (edges.size() >= 1000 || nodes.size() >= 200) {
                            truncated = true;
                            break;
                        }
                        edges.add(edge);
                        for (String endpoint : List.of(edge.subject(), edge.object())) {
                            if (nodes.add(endpoint) && endpoint.startsWith(BASE + "concept/")) {
                                frontier.addLast(endpoint);
                            }
                        }
                    }
                    if (truncated) break;
                }
                if (truncated) break;
            }
            return new Neighborhood(iri, List.copyOf(nodes), List.copyOf(edges), depth, truncated);
        });
    }

    public List<Evidence> provenance(String conceptIri, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit outside 1..200");
        if (!conceptIri.startsWith(BASE + "concept/")) throw new IllegalArgumentException("Unrecognized IRI");
        return store.read(ds -> {
            Model provenance = ds.getNamedModel(BASE + "graph/provenance");
            var concept = provenance.getResource(conceptIri);
            var list = new ArrayList<Evidence>();
            var statements = provenance.listResourcesWithProperty(RDF.subject, concept);
            while (statements.hasNext() && list.size() < limit) {
                var assertion = statements.nextResource();
                list.add(new Evidence(assertion.getURI(),
                        value(assertion, RDF.predicate),
                        value(assertion, RDF.object),
                        value(assertion, ResourceFactory.createProperty("http://purl.org/dc/terms/source")),
                        value(assertion, ResourceFactory.createProperty("http://www.w3.org/ns/prov#wasDerivedFrom")),
                        value(assertion, ResourceFactory.createProperty("http://purl.org/dc/terms/identifier"))));
            }
            return list.stream().sorted(Comparator.comparing(Evidence::assertionIri)).toList();
        });
    }

    private static String value(Resource subject, org.apache.jena.rdf.model.Property predicate) {
        Statement statement = subject.getProperty(predicate);
        if (statement == null) return null;
        var object = statement.getObject();
        return object.isResource() ? object.asResource().getURI() : object.asLiteral().getString();
    }
}
