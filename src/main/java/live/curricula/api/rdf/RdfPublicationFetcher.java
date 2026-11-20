package live.curricula.api.rdf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Fetches pinned artifacts; never follows a moving branch for production activation. */
public final class RdfPublicationFetcher {
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public Path fetch(URI immutableBaseUrl, String pinnedManifestSha256, Path destination)
            throws IOException, InterruptedException {
        String base = immutableBaseUrl.toString();
        if (!"https".equalsIgnoreCase(immutableBaseUrl.getScheme()) || !base.endsWith("/")) {
            throw new IllegalArgumentException("Immutable RDF artifact URL must use HTTPS and end in /");
        }
        Files.createDirectories(destination);
        for (String name : java.util.stream.Stream.concat(
                java.util.stream.Stream.of("manifest.json"), RdfReleaseVerifier.FILES.stream()).toList()) {
            URI uri = immutableBaseUrl.resolve(name);
            if (!uri.getHost().equalsIgnoreCase(immutableBaseUrl.getHost())) {
                throw new IOException("Cross-host RDF artifact redirect is not permitted");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(2)).GET().build();
            Path temp = destination.resolve(name + ".part");
            try {
                HttpResponse<Path> response = http.send(request,
                        HttpResponse.BodyHandlers.ofFile(temp));
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
                Files.move(temp, destination.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        RdfReleaseVerifier.verify(destination, pinnedManifestSha256);
        return destination;
    }
}
