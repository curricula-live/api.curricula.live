package live.curricula.api.http;

import live.curricula.api.catalog.CatalogUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiProblemHandler {
    @ExceptionHandler(CatalogUnavailableException.class)
    ProblemDetail unavailable(CatalogUnavailableException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Knowledge dataset unavailable");
        return problem;
    }
}
