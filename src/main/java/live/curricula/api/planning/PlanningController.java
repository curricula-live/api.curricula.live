package live.curricula.api.planning;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.planning.PlanningStore.Account;
import live.curricula.api.planning.PlanningStore.Draft;
import live.curricula.api.planning.PlanningStore.Plan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/planning/plans")
@ConditionalOnProperty(name = "CURRICULA_PLANNING_ENABLED", havingValue = "true")
public final class PlanningController {
    private final PlanningStore plans;
    private final JenaKnowledgeCatalog concepts;

    public PlanningController(PlanningStore plans, JenaKnowledgeCatalog concepts) {
        this.plans = plans;
        this.concepts = concepts;
    }

    public record Change(String title, String conceptIri, long revision) { }

    @GetMapping
    public List<Plan> list(@AuthenticationPrincipal Jwt principal,
                           @RequestParam(defaultValue = "0") int offset) throws SQLException {
        return plans.list(owner(principal), offset);
    }

    @GetMapping("/{id}")
    public Plan find(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) throws SQLException {
        return plans.find(owner(principal), id).orElseThrow(PlanningController::unavailable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Plan create(@AuthenticationPrincipal Jwt principal, @RequestBody Change change) throws SQLException {
        Draft draft = draft(change);
        Account owner = owner(principal);
        plans.registerVerifiedAccount(owner);
        return plans.create(owner, draft);
    }

    @PutMapping("/{id}")
    public Plan update(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id,
                       @RequestBody Change change) throws SQLException {
        return plans.update(owner(principal), id, change.revision(), draft(change)).orElseThrow(PlanningController::unavailable);
    }

    @ExceptionHandler(SQLException.class)
    ProblemDetail databaseUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Planning storage unavailable");
    }

    private Draft draft(Change change) {
        var draft = new Draft(change.title(), change.conceptIri());
        if (concepts.findByIri(draft.conceptIri()).isEmpty()) throw new IllegalArgumentException("Unknown canonical concept");
        return draft;
    }

    private static Account owner(Jwt principal) {
        return new Account(principal.getClaimAsString("iss"), principal.getSubject());
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan unavailable or changed");
    }
}
