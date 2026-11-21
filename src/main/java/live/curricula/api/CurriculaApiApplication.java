package live.curricula.api;

import java.io.IOException;
import java.nio.file.Path;
import live.curricula.api.rdf.RdfReleaseStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CurriculaApiApplication {

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "rdf-rollback".equals(args[0])) {
            String storage = System.getenv("CURRICULA_RDF_STORE");
            if (storage == null || storage.isBlank()) {
                throw new IllegalArgumentException("Set CURRICULA_RDF_STORE to the offline RDF store before rollback");
            }
            try (var store = new RdfReleaseStore(Path.of(storage))) {
                var release = store.rollback();
                System.out.printf("Activated RDF %s (%s)%n", release.version(), release.manifestSha256());
            }
            return;
        }
        SpringApplication.run(CurriculaApiApplication.class, args);
    }

}
