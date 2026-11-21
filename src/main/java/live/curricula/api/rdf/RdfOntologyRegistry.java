package live.curricula.api.rdf;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

/** Query-side ownership of concept, vocabulary and graph IRIs. */
public final class RdfOntologyRegistry {
    public static final Producer FORMAL_SCIENCES = new Producer(
            "formal-sciences", RdfReleaseStore.BASE);
    public static final RdfOntologyRegistry DEFAULT = new RdfOntologyRegistry(List.of(FORMAL_SCIENCES));
    public static final int MAX_IRI_LENGTH = 2048;

    public record Producer(String id, String namespace) {
        public Producer {
            if (id == null || !id.matches("[a-z][-a-z0-9]*")) {
                throw new IllegalArgumentException("Invalid ontology producer ID");
            }
            requireAbsoluteIri(namespace);
            if (!namespace.endsWith("/")) {
                throw new IllegalArgumentException("Ontology namespace must end in /");
            }
        }

        public String conceptIri(String localName) { return namespace + "concept/" + localName; }
        public String graphIri(String layer) { return namespace + "graph/" + layer; }
        public String schemeIri(String localName) { return namespace + "scheme/" + localName; }
        public boolean ownsConcept(String iri) {
            return iri != null && iri.length() > conceptIri("").length() && iri.startsWith(conceptIri(""));
        }
        public String localName(String iri) { return iri.substring(conceptIri("").length()); }
        public boolean ownsPredicate(String iri) { return iri.startsWith(namespace + "vocab/"); }
    }

    private final List<Producer> producers;

    public RdfOntologyRegistry(List<Producer> producers) {
        this.producers = List.copyOf(producers);
        for (int index = 0; index < this.producers.size(); index++) {
            Producer producer = this.producers.get(index);
            for (Producer previous : this.producers.subList(0, index)) {
                if (producer.id().equals(previous.id()) ||
                        producer.namespace().startsWith(previous.namespace()) ||
                        previous.namespace().startsWith(producer.namespace())) {
                    throw new IllegalArgumentException("Overlapping ontology producers");
                }
            }
        }
    }

    public List<Producer> producers() { return producers; }

    public Optional<Producer> ownerOf(String iri) {
        return producers.stream().filter(producer -> producer.ownsConcept(iri)).findFirst();
    }

    public Optional<Resource> concept(Dataset dataset, String iri) {
        return ownerOf(iri).flatMap(producer -> {
            Model asserted = dataset.getNamedModel(producer.graphIri("asserted"));
            Resource node = asserted.getResource(iri);
            return asserted.contains(node, RDF.type, SKOS.Concept) ? Optional.of(node) : Optional.empty();
        });
    }

    public static Optional<String> preferredLabel(Resource concept) {
        String label = null;
        int best = Integer.MAX_VALUE;
        var iterator = concept.listProperties(SKOS.prefLabel);
        try {
            while (iterator.hasNext()) {
                RDFNode value = iterator.nextStatement().getObject();
                if (!value.isLiteral()) continue;
                var literal = value.asLiteral();
                String language = literal.getLanguage();
                int rank = language.equalsIgnoreCase("en") ? 0
                        : language.regionMatches(true, 0, "en-", 0, 3) ? 1
                        : language.isEmpty() ? 2 : 3;
                String candidate = literal.getString();
                if (rank < best || (rank == best && candidate.compareTo(label) < 0)) {
                    best = rank;
                    label = candidate;
                }
            }
        } finally {
            iterator.close();
        }
        return Optional.ofNullable(label);
    }

    public static void requireAbsoluteIri(String iri) {
        if (iri != null && iri.length() <= MAX_IRI_LENGTH) {
            try {
                if (new URI(iri).isAbsolute()) return;
            } catch (URISyntaxException ignored) {
                // Keep malformed request content out of error details.
            }
        }
        throw new IllegalArgumentException("Expected an absolute RDF IRI of up to 2048 characters");
    }
}
