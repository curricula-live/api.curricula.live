package live.curricula.api.http;

import java.util.List;
import live.curricula.api.catalog.ConceptSummary;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.rdf.RdfKnowledgeExplorer;
import live.curricula.api.rdf.RdfReleaseStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Public read-only ontology API. Private ingest operations are deliberately not exposed. */
@RestController
@RequestMapping("/api/v1")
public final class RdfApiController {
    private final JenaKnowledgeCatalog catalog;
    private final RdfKnowledgeExplorer explorer;
    private final RdfReleaseStore releases;

    public RdfApiController(JenaKnowledgeCatalog catalog, RdfKnowledgeExplorer explorer, RdfReleaseStore releases) {
        this.catalog = catalog;
        this.explorer = explorer;
        this.releases = releases;
    }

    @GetMapping("/knowledge/publication")
    public RdfReleaseStore.Status publication() { return releases.status(); }

    @GetMapping("/concepts/by-iri")
    public ConceptSummary byIri(@RequestParam String iri) {
        return catalog.findByIri(iri).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canonical concept not found"));
    }

    @GetMapping("/concepts/{slug}/relationships")
    public List<RdfKnowledgeExplorer.Relationship> relationships(
            @PathVariable String slug, @RequestParam(defaultValue = "all") String graph,
            @RequestParam(defaultValue = "100") int limit) {
        return explorer.relationships(iri(slug), graph, limit);
    }

    @GetMapping("/concepts/{slug}/neighborhood")
    public RdfKnowledgeExplorer.Neighborhood neighborhood(
            @PathVariable String slug, @RequestParam(defaultValue = "1") int depth) {
        return explorer.neighborhood(iri(slug), depth);
    }

    @GetMapping("/concepts/{slug}/provenance")
    public List<RdfKnowledgeExplorer.Evidence> provenance(
            @PathVariable String slug, @RequestParam(defaultValue = "100") int limit) {
        return explorer.provenance(iri(slug), limit);
    }

    private String iri(String slug) {
        if (!slug.matches("[a-z0-9][-a-z0-9]*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid canonical slug");
        }
        var found = catalog.findBySlug(slug);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Canonical concept not found");
        }
        return found.get().iri();
    }
}
