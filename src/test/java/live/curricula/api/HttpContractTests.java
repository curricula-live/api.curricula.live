package live.curricula.api;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpContractTests {
    @TempDir static Path rdfStore;

    @DynamicPropertySource
    static void storePath(DynamicPropertyRegistry registry) {
        registry.add("CURRICULA_RDF_STORE", () -> rdfStore.toString());
    }

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test void bootstrapStatusIsHonest() throws Exception {
        var response = get("/api/v1/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"knowledgeAvailable\":false"));
    }
    @Test void noFakeKnowledgeWithoutDataset() throws Exception {
        assertEquals(503, get("/api/v1/concepts").statusCode());
        assertEquals(503, get("/api/v1/concepts/binary-search").statusCode());
    }
    @Test void searchChecksInputBeforeContactingRdf() throws Exception {
        assertEquals(400, get("/api/v1/search?q=").statusCode());
        assertEquals(503, get("/api/v1/search?q=binary").statusCode());
    }
    @Test void connectionSearchChecksInputAndHonorsUnavailableStore() throws Exception {
        assertEquals(400, get("/api/v1/relationships/search").statusCode());
        assertEquals(400, get("/api/v1/relationships/search?q=%21").statusCode());
        assertEquals(400, get("/api/v1/relationships/search?q=uses&limit=101").statusCode());
        assertEquals(503, get("/api/v1/relationships/search?q=uses").statusCode());
    }
    @Test void planningIsDisabledByDefault() throws Exception {
        assertEquals(403, get("/api/v1/planning/plans").statusCode());
    }
    @Test void actuatorHealth() throws Exception {
        assertEquals(200, get("/actuator/health").statusCode());
    }
}
