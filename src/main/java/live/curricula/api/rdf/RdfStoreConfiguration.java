package live.curricula.api.rdf;

import java.io.IOException;
import java.nio.file.Path;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RdfStoreConfiguration {
    @Bean(destroyMethod = "close")
    RdfReleaseStore releaseStore(Environment environment) throws IOException {
        String storage = environment.getProperty("CURRICULA_RDF_STORE", "./var/rdf");
        RdfReleaseStore store = new RdfReleaseStore(Path.of(storage));
        String packageDirectory = environment.getProperty("CURRICULA_RDF_PACKAGE_DIR");
        if (packageDirectory != null && !packageDirectory.isBlank()) {
            try {
                store.ingest(Path.of(packageDirectory),
                        environment.getProperty("CURRICULA_RDF_MANIFEST_SHA256"),
                        environment.getProperty("CURRICULA_RDF_COMMIT"));
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
