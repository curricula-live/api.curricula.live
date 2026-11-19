package live.curricula.api.catalog;

public final class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException() {
        super("No validated RDF dataset is connected yet. The API is in bootstrap mode.");
    }
}
