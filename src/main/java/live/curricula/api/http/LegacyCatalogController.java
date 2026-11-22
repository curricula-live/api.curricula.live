package live.curricula.api.http;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only v1 wire shapes over the active formal-sciences corpus. */
@RestController
public final class LegacyCatalogController {
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private final JenaKnowledgeCatalog catalog;

    public LegacyCatalogController(JenaKnowledgeCatalog catalog) {
        this.catalog = catalog;
    }

    public record LegacyConcept(String slug) { }
    public record Results(List<LegacyConcept> results) { }
    public record Search(String query, Map<String, List<LegacyConcept>> results) { }

    @GetMapping("/")
    public Map<String, Object> discovery() {
        return Map.of("service", "curricula.live API", "latest_version", "v1", "versions", Map.of("v1", "/v1/"));
    }

    @GetMapping({"/health", "/health/"})
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "curricula.live api");
    }

    @GetMapping({"/v1/concepts", "/v1/concepts/"})
    public Results concepts() {
        return new Results(catalog.canonicalSlugs().stream().map(LegacyConcept::new).toList());
    }

    @GetMapping({"/v1/concepts/{slug}", "/v1/concepts/{slug}/"})
    public LegacyConcept concept(@PathVariable String slug) {
        return catalog.findBySlug(slug).map(item -> new LegacyConcept(item.slug()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canonical concept not found"));
    }

    @GetMapping({"/v1/search", "/v1/search/"})
    public Search search(@RequestParam(defaultValue = "") String q,
                         @RequestParam(defaultValue = "all") String category,
                         @RequestParam(defaultValue = "20") int limit) {
        String normalized = normalize(q);
        if (normalized.isEmpty() || q.codePointCount(0, q.length()) > 160 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide searchable text (1..160 characters) and a limit of 1..100");
        }
        if (category.equals("all") || category.equals("connections")) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Legacy relation UUIDs require a reviewed crosswalk. Use category=concepts or the RDF-native API");
        }
        if (!category.equals("concepts")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown search category");
        }
        var variants = List.of(normalized, normalized.replace("-", "_"), normalized.replace("-", ""));
        var ranked = catalog.canonicalSlugs().stream()
                .filter(slug -> variants.stream().anyMatch(slug::contains))
                .sorted(Comparator.comparing((String slug) -> !variants.contains(slug)).thenComparing(Comparator.naturalOrder()))
                .limit(limit).map(LegacyConcept::new).toList();
        return new Search(q.strip(), Map.of("concepts", ranked));
    }

    private static String normalize(String query) {
        String parts = NON_SLUG.matcher(query.strip().toLowerCase(Locale.ROOT).replace("'", "").replace("’", ""))
                .replaceAll("-");
        return parts.replaceAll("^-|-$", "");
    }
}
