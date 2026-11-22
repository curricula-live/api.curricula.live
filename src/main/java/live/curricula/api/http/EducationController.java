package live.curricula.api.http;

import java.util.NoSuchElementException;
import live.curricula.api.education.EducationCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class EducationController {
    private final EducationCatalog catalog;

    public EducationController(EducationCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/api/v1/education")
    public EducationCatalog.Context context(@RequestParam String iri) { return catalog.context(iri); }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail unknownConcept() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Canonical concept not found");
    }
}
