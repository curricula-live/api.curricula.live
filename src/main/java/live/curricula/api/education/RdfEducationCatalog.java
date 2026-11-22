package live.curricula.api.education;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import live.curricula.api.rdf.RdfOntologyRegistry;
import live.curricula.api.rdf.RdfQueryStore;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class RdfEducationCatalog implements EducationCatalog {
    public static final String VOCAB = "https://curricula.live/ns/education#";
    private static final int LIMIT = 100;
    private static final Comparator<Record> ORDER = Comparator.comparing(Record::value)
            .thenComparing(Record::language).thenComparing(Record::graphIri);
    private final RdfQueryStore store;
    private final RdfOntologyRegistry registry;
    private final List<RdfOntologyRegistry.Producer> producers;

    public RdfEducationCatalog(RdfQueryStore store, RdfOntologyRegistry registry,
                               @Value("${CURRICULA_RDF_EDUCATION_PRODUCERS:}") String configured) {
        this.store = store;
        this.registry = registry;
        var ids = Arrays.stream(configured.split(",", -1)).map(String::strip)
                .filter(id -> !id.isBlank()).collect(Collectors.toSet());
        producers = registry.producers().stream().filter(p -> ids.contains(p.id())).toList();
        if (producers.size() != ids.size()) {
            throw new IllegalArgumentException("An educational producer is not registered");
        }
    }

    @Override public Context context(String conceptIri) {
        RdfOntologyRegistry.requireAbsoluteIri(conceptIri);
        return store.read(dataset -> {
            if (registry.concept(dataset, conceptIri).isEmpty()) throw new NoSuchElementException();
            var prerequisites = new Results();
            var mappings = new Results();
            var notes = new Results();
            boolean available = false;
            for (var producer : producers) {
                String graph = producer.graphIri("asserted");
                if (!dataset.containsNamedModel(graph)) continue;
                available = true;
                var model = dataset.getNamedModel(graph);
                var concept = model.getResource(conceptIri);
                each(model.listStatements(null, ResourceFactory.createProperty(VOCAB + "prerequisiteOf"), concept),
                        row -> {
                            if (row.getSubject().isURIResource() && !row.getSubject().equals(concept)
                                    && registry.concept(dataset, row.getSubject().getURI()).isPresent()) {
                                prerequisites.add(new Record(row.getSubject().getURI(), "", graph));
                            }
                        });
                each(model.listStatements(concept, ResourceFactory.createProperty(VOCAB + "curriculumMapping"), (org.apache.jena.rdf.model.RDFNode) null),
                        row -> {
                            if (row.getObject().isURIResource()) mappings.add(new Record(row.getResource().getURI(), "", graph));
                        });
                each(model.listStatements(concept, ResourceFactory.createProperty(VOCAB + "teachingNote"), (org.apache.jena.rdf.model.RDFNode) null),
                        row -> {
                            if (row.getObject().isLiteral()) {
                                var literal = row.getLiteral();
                                notes.add(new Record(literal.getString(), literal.getLanguage(), graph));
                            }
                        });
            }
            return new Context(conceptIri, available, prerequisites.copy(), mappings.copy(), notes.copy(),
                    prerequisites.truncated || mappings.truncated || notes.truncated);
        });
    }

    private static void each(StmtIterator iterator, Consumer<Statement> action) {
        try { iterator.forEachRemaining(action); } finally { iterator.close(); }
    }

    private static final class Results {
        private final TreeSet<Record> records = new TreeSet<>(ORDER);
        private boolean truncated;

        void add(Record record) {
            records.add(record);
            if (records.size() > LIMIT) {
                records.pollLast();
                truncated = true;
            }
        }

        List<Record> copy() { return List.copyOf(records); }
    }
}
