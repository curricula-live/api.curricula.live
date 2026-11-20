package live.curricula.api.http;

import java.util.List;
import java.util.NoSuchElementException;
import live.curricula.api.catalog.ConceptSummary;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.rdf.RdfKnowledgeExplorer;
import live.curricula.api.rdf.RdfKnowledgeExplorer.Evidence;
import live.curricula.api.rdf.RdfKnowledgeExplorer.Neighborhood;
import live.curricula.api.rdf.RdfKnowledgeExplorer.Relationship;
import live.curricula.api.rdf.RdfKnowledgeExplorer.RelationshipFilter;
import live.curricula.api.rdf.RdfOntologyRegistry;
import live.curricula.api.rdf.RdfReleaseStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Public read-only queries over published RDF. */
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
        RdfOntologyRegistry.requireAbsoluteIri(iri);
        return catalog.findByIri(iri).orElseThrow(NoSuchElementException::new);
    }

    // Query records supply identical defaults and validation regardless of concept addressing.
    public record EdgeQuery(String graph, String kind, String predicate, String domain) {
        RelationshipFilter filter() {
            return new RelationshipFilter(graph == null ? "all" : graph, kind == null ? "all" : kind,
                    predicate, domain);
        }
    }

    @GetMapping("/concepts/{slug}/relationships")
    public List<Relationship> relationships(
            @PathVariable String slug, EdgeQuery query, @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return relationshipsByIri(iri(slug), query, offset, limit);
    }

    @GetMapping("/concepts/by-iri/relationships")
    public List<Relationship> relationshipsByIri(
            @RequestParam String iri, EdgeQuery query, @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return explorer.relationships(iri, query.filter(), offset, limit);
    }

    @GetMapping("/concepts/{slug}/neighborhood")
    public Neighborhood neighborhood(
            @PathVariable String slug, EdgeQuery query, @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "1000") int limit) {
        return neighborhoodByIri(iri(slug), query, depth, limit);
    }

    @GetMapping("/concepts/by-iri/neighborhood")
    public Neighborhood neighborhoodByIri(
            @RequestParam String iri, EdgeQuery query, @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "1000") int limit) {
        return explorer.neighborhood(iri, depth, query.filter(), limit);
    }

    @GetMapping("/concepts/{slug}/provenance")
    public List<Evidence> provenance(
            @PathVariable String slug, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "outgoing") String direction,
            @RequestParam(required = false) String predicate, @RequestParam(required = false) String adjacent) {
        return provenanceByIri(iri(slug), limit, direction, predicate, adjacent);
    }

    @GetMapping("/concepts/by-iri/provenance")
    public List<Evidence> provenanceByIri(
            @RequestParam String iri, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "outgoing") String direction,
            @RequestParam(required = false) String predicate, @RequestParam(required = false) String adjacent) {
        return explorer.provenance(iri, limit, direction, predicate, adjacent);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail conceptMissing() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Canonical concept not found");
    }

    private String iri(String slug) {
        if (slug.length() > 2048 || !slug.matches("[a-z0-9][-a-z0-9]*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid canonical slug");
        }
        return RdfOntologyRegistry.FORMAL_SCIENCES.conceptIri(slug);
    }
}
