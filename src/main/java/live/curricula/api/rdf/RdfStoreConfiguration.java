package live.curricula.api.rdf;

import java.io.IOException;
import java.nio.file.Path;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.catalog.KnowledgeCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RdfStoreConfiguration {
    @Bean(destroyMethod = "close")
    RdfReleaseStore releaseStore() throws IOException {
        String storage = System.getenv().getOrDefault("CURRICULA_RDF_STORE", "./var/rdf");
        RdfReleaseStore store = new RdfReleaseStore(Path.of(storage));
        String packageDirectory = System.getenv("CURRICULA_RDF_PACKAGE_DIR");
        if (packageDirectory != null && !packageDirectory.isBlank()) {
            try {
                store.ingest(Path.of(packageDirectory),
                        System.getenv("CURRICULA_RDF_MANIFEST_SHA256"),
                        System.getenv("CURRICULA_RDF_COMMIT"));
            } catch (IOException | RuntimeException exception) {
                store.close();
                throw exception;
            }
        }
        return store;
    }

    @Bean
    RdfKnowledgeExplorer explorer(RdfReleaseStore store) {
        return new RdfKnowledgeExplorer(store);
    }

    @Bean
    JenaKnowledgeCatalog knowledgeCatalog(RdfReleaseStore store) {
        return new JenaKnowledgeCatalog(store);
    }
}
