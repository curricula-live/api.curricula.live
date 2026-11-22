package live.curricula.api.rdf;

import java.io.IOException;
import java.nio.file.Path;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RdfStoreConfiguration {
    @Bean
    RdfOntologyRegistry ontologyRegistry(Environment environment) {
        return RdfPublications.registry(environment.getProperty("CURRICULA_RDF_PRODUCERS"));
    }

    @Bean(destroyMethod = "close")
    RdfPublications releaseStore(Environment environment, RdfOntologyRegistry registry) throws IOException {
        String storage = environment.getProperty("CURRICULA_RDF_STORE", "./var/rdf");
        RdfPublications store = new RdfPublications(Path.of(storage), registry);
        String packageDirectory = environment.getProperty("CURRICULA_RDF_PACKAGE_DIR");
        if (packageDirectory != null && !packageDirectory.isBlank()) {
            try {
                store.ingest("formal-sciences", Path.of(packageDirectory),
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
    RdfKnowledgeExplorer explorer(RdfQueryStore store, RdfOntologyRegistry registry) {
        return new RdfKnowledgeExplorer(store, registry);
    }

    @Bean
    JenaKnowledgeCatalog knowledgeCatalog(RdfQueryStore store, RdfOntologyRegistry registry) {
        return new JenaKnowledgeCatalog(store, registry);
    }
}
