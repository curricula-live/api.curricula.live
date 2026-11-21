package live.curricula.api.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiInputProblemHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
