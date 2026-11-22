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
}
