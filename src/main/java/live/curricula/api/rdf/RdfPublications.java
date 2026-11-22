package live.curricula.api.rdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import live.curricula.api.catalog.CatalogUnavailableException;
import live.curricula.api.rdf.RdfOntologyRegistry.Producer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.sparql.core.DatasetGraphReadOnly;

/** Independent TDB2 publications sharing a read-only view of their owned named graphs. */
public final class RdfPublications implements RdfQueryStore, AutoCloseable {
    private final Map<String, RdfReleaseStore> byId = new LinkedHashMap<>();
    private final List<RdfReleaseStore> stores;

    public RdfPublications(Path root, RdfOntologyRegistry registry) throws IOException {
        try {
            for (Producer producer : registry.producers()) {
                Path storage = producer.equals(RdfOntologyRegistry.FORMAL_SCIENCES)
                        ? root : root.resolve("producers").resolve(producer.id());
                byId.put(producer.id(), new RdfReleaseStore(storage, producer));
            }
            stores = List.copyOf(byId.values());
        } catch (IOException | RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static RdfOntologyRegistry registry(String extra) {
        List<Producer> producers = new ArrayList<>(List.of(RdfOntologyRegistry.FORMAL_SCIENCES));
        if (extra != null && !extra.isBlank()) {
            for (String entry : extra.split(",", -1)) {
                String[] pair = entry.strip().split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException("Expected producer-id=namespace");
                producers.add(new Producer(pair[0].strip(), pair[1].strip()));
            }
        }
        return new RdfOntologyRegistry(producers);
    }

    public RdfReleaseStore.Status ingest(String producer, Path dir, String digest, String commit) throws IOException {
        return store(producer).ingest(dir, digest, commit);
    }

    public RdfReleaseStore.Status rollback(String producer) throws IOException { return store(producer).rollback(); }

    public Map<String, RdfReleaseStore.Status> statuses() {
        var result = new LinkedHashMap<String, RdfReleaseStore.Status>();
        byId.forEach((id, store) -> result.put(id, store.status()));
        return result;
    }

    @Override public RdfReleaseStore.Status status() { return status("formal-sciences"); }
    @Override public RdfReleaseStore.Status status(String id) { return store(id).status(); }
    @Override public <T> T readProducer(String id, Function<Dataset, T> query) { return store(id).read(query); }
    @Override public boolean available() { return stores.stream().anyMatch(RdfReleaseStore::available); }
    @Override public <T> T readFormal(Function<Dataset, T> query) { return store("formal-sciences").read(query); }

    @Override public <T> T read(Function<Dataset, T> query) {
        if (stores.size() == 1) return stores.getFirst().read(query);
        return link(0, DatasetFactory.createGeneral(), false, query);
    }

    private <T> T link(int index, Dataset view, boolean active, Function<Dataset, T> query) {
        if (index == stores.size()) {
            if (!active) throw new CatalogUnavailableException();
            // DatasetGraphMapLink stores graph references. The underlying stores
            // hold their read transactions until the caller has finished.
            return query.apply(DatasetFactory.wrap(new DatasetGraphReadOnly(view.asDatasetGraph())));
        }
        return stores.get(index).readIfActive(dataset -> {
            dataset.listNames().forEachRemaining(name -> view.addNamedModel(name, dataset.getNamedModel(name)));
            return link(index + 1, view, true, query);
        }, () -> link(index + 1, view, active, query));
    }

    private RdfReleaseStore store(String producer) {
        RdfReleaseStore found = byId.get(producer);
        if (found == null) throw new IllegalArgumentException("Unknown ontology producer");
        return found;
    }

    @Override public void close() {
        RuntimeException failure = null;
        for (RdfReleaseStore store : new ArrayList<>(byId.values()).reversed()) {
            try { store.close(); } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
