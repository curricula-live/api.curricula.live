package live.curricula.api.rdf;

import java.util.function.Function;
import org.apache.jena.query.Dataset;

/** Read transactions over the named RDF layers available to this catalog. */
public interface RdfQueryStore {
    <T> T read(Function<Dataset, T> query);

    /** The original formal-sciences publication status. */
    RdfReleaseStore.Status status();

    default boolean available() { return status().available(); }

    default <T> T readFormal(Function<Dataset, T> query) { return read(query); }

    default <T> T readProducer(String id, Function<Dataset, T> query) {
        requireFormal(id);
        return readFormal(query);
    }

    default RdfReleaseStore.Status status(String id) {
        requireFormal(id);
        return status();
    }

    private static void requireFormal(String id) {
        if (!"formal-sciences".equals(id)) throw new IllegalArgumentException("Unknown ontology producer");
    }
}
