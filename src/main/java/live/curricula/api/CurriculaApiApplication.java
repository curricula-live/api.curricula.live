package live.curricula.api;

import java.io.IOException;
import java.nio.file.Path;
import live.curricula.api.rdf.RdfPublications;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class CurriculaApiApplication {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && ("rdf-rollback".equals(args[0]) || "rdf-import".equals(args[0]))) {
            String storage = System.getenv("CURRICULA_RDF_STORE");
            if (storage == null || storage.isBlank()) {
                throw new IllegalArgumentException("Set CURRICULA_RDF_STORE to the offline RDF store");
            }
            var registry = RdfPublications.registry(System.getenv("CURRICULA_RDF_PRODUCERS"));
            try (var store = new RdfPublications(Path.of(storage), registry)) {
                var status = switch (args[0]) {
                    case "rdf-rollback" -> {
                        if (args.length > 2) throw new IllegalArgumentException("Usage: rdf-rollback [producer]");
                        yield store.rollback(args.length == 2 ? args[1] : "formal-sciences");
                    }
                    default -> {
                        if (args.length != 5) throw new IllegalArgumentException("Usage: rdf-import producer path digest commit");
                        yield store.ingest(args[1], Path.of(args[2]), args[3], args[4]);
                    }
                };
                System.out.printf("Activated RDF %s (%s)%n", status.version(), status.manifestSha256());
            }
            return;
        }
        SpringApplication.run(CurriculaApiApplication.class, args);
    }

}
