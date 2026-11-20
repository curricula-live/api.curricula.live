package live.curricula.api;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class CurriculaApiApplicationTests {
    @TempDir static Path rdfStore;

    @DynamicPropertySource
    static void storePath(DynamicPropertyRegistry registry) {
        registry.add("CURRICULA_RDF_STORE", () -> rdfStore.toString());
    }


	@Test
	void contextLoads() {
	}

}
