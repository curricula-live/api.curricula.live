package live.curricula.api.catalog;

import java.util.List;
import java.util.Optional;

/** Read-only port; a future Apache Jena implementation consumes published RDF. */
public interface KnowledgeCatalog {
    List<ConceptSummary> list(int limit);
    List<ConceptSummary> search(String query, int limit);
    Optional<ConceptSummary> findBySlug(String slug);
    boolean available();
}
