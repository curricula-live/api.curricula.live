package live.curricula.api.http;

import java.util.List;
import live.curricula.api.catalog.ConceptSummary;
import live.curricula.api.catalog.KnowledgeCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final KnowledgeCatalog catalog;

    public CatalogController(KnowledgeCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/status")
    public Status status() {
        return new Status("curricula-api", "bootstrap", catalog.available());
    }

    @GetMapping("/concepts")
    public List<ConceptSummary> concepts(@RequestParam(defaultValue = "25") int limit) {
        checkLimit(limit);
        return catalog.list(limit);
    }

    @GetMapping("/concepts/{slug}")
    public ConceptSummary concept(@PathVariable String slug) {
        return catalog.findBySlug(slug).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Concept not found"));
    }

    @GetMapping("/search")
    public SearchResults search(@RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "25") int limit) {
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query must not be blank");
        }
        checkLimit(limit);
        return new SearchResults(q.trim(), catalog.search(q.trim(), limit));
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
    }

    public record Status(String service, String stage, boolean knowledgeAvailable) {}
    public record SearchResults(String query, List<ConceptSummary> concepts) {}
}
