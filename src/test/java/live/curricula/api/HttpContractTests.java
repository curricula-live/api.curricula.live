package live.curricula.api;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpContractTests {
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
    @Test void actuatorHealth() throws Exception {
        assertEquals(200, get("/actuator/health").statusCode());
    }
}
