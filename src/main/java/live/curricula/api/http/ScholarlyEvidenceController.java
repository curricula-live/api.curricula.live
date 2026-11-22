package live.curricula.api.http;

import java.util.NoSuchElementException;
import live.curricula.api.rdf.RdfCitationCatalog;
import live.curricula.api.rdf.RdfCitationCatalog.Citation;
import live.curricula.api.rdf.RdfCitationCatalog.Page;
import live.curricula.api.rdf.RdfCitationCatalog.Source;
import org.apache.jena.query.QueryCancelledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ScholarlyEvidenceController {
    private final RdfCitationCatalog catalog;

    public ScholarlyEvidenceController(RdfCitationCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/scholarly/sources")
    public Page<Source> sources(@RequestParam(defaultValue = "0") int offset,
                                @RequestParam(defaultValue = "25") int limit,
                                @RequestParam(required = false) String publication,
                                @RequestParam(defaultValue = "formal-sciences") String producer) {
        return catalog.sources(producer, offset, limit, publication);
    }

    @GetMapping("/api/v1/scholarly/assertions")
    public Page<Citation> assertions(@RequestParam String source, @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "25") int limit,
                                     @RequestParam(required = false) String publication,
                                     @RequestParam(defaultValue = "formal-sciences") String producer) {
        return catalog.citations(producer, source, null, offset, limit, publication);
    }

    @GetMapping("/api/v1/concepts/{slug}/evidence")
    public Page<Citation> evidence(@PathVariable String slug, @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "25") int limit,
                                   @RequestParam(required = false) String publication) {
        if (!slug.matches("[a-z0-9][-a-z0-9]{0,127}")) throw new IllegalArgumentException("Invalid canonical slug");
        return catalog.citations(null, slug, offset, limit, publication);
    }

    @ExceptionHandler(RdfCitationCatalog.PublicationChanged.class)
    ProblemDetail stalePage() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The RDF publication changed. Start at the first page.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail unknownConcept() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Canonical concept not found");
    }

    @ExceptionHandler(QueryCancelledException.class)
    ProblemDetail timeout() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Evidence query timed out");
    }
}
