package live.curricula.api.catalog;

import java.util.List;
import java.util.Optional;

/** Explicitly fail rather than serving fabricated ontology records. */
public final class UnconfiguredKnowledgeCatalog implements KnowledgeCatalog {
    @Override public List<ConceptSummary> list(int limit) { throw new CatalogUnavailableException(); }
    @Override public List<ConceptSummary> search(String query, int limit) { throw new CatalogUnavailableException(); }
    @Override public Optional<ConceptSummary> findBySlug(String slug) { throw new CatalogUnavailableException(); }
    @Override public boolean available() { return false; }
}
