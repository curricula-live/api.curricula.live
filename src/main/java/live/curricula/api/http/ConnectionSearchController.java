package live.curricula.api.http;

import java.util.List;
import live.curricula.api.rdf.RdfConnectionSearch;
import org.apache.jena.query.QueryCancelledException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class ConnectionSearchController {
    private final RdfConnectionSearch search;

    public ConnectionSearchController(RdfConnectionSearch search) {
        this.search = search;
    }

    @GetMapping("/api/v1/relationships/search")
    public SearchResults search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        try {
            return new SearchResults(q.strip(), search.search(q, limit));
        } catch (QueryCancelledException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Connection search timed out", exception);
        }
    }

    public record SearchResults(String query, List<RdfConnectionSearch.Connection> connections) { }
}
