package live.curricula.api.http;

import java.util.Map;
import live.curricula.api.rdf.RdfPublications;
import live.curricula.api.rdf.RdfReleaseStore.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PublicationController {
    private final RdfPublications store;

    public PublicationController(RdfPublications store) { this.store = store; }

    @GetMapping("/api/v1/knowledge/publications")
    public Map<String, Status> publications() { return store.statuses(); }
}
