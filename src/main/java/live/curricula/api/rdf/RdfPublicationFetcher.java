package live.curricula.api.rdf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;

/** Fetches pinned artifacts into a private directory before publishing a complete package. */
public final class RdfPublicationFetcher {
    @FunctionalInterface
    interface Download {
        void get(URI source, Path destination) throws IOException, InterruptedException;
    }

    private final Download download;

    public RdfPublicationFetcher() {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        download = (source, destination) -> {
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<Path> response = http.send(request, info -> info.statusCode() == 200
                    ? HttpResponse.BodySubscribers.ofFile(destination)
                    : HttpResponse.BodySubscribers.replacing(destination));
            if (response.statusCode() != 200) {
                throw new IOException("RDF artifact fetch returned HTTP " + response.statusCode() + ": " + source);
            }
        };
    }

    RdfPublicationFetcher(Download download) {
        this.download = download;
    }

    public Path fetch(URI immutableBaseUrl, String pinnedManifestSha256, Path destination)
            throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(immutableBaseUrl.getScheme()) || immutableBaseUrl.getHost() == null
                || !immutableBaseUrl.getRawPath().endsWith("/") || immutableBaseUrl.getRawUserInfo() != null
                || immutableBaseUrl.getRawQuery() != null || immutableBaseUrl.getRawFragment() != null) {
            throw new IllegalArgumentException("RDF artifact base must be an HTTPS directory URL without credentials, query or fragment");
        }
        if (!RdfReleaseVerifier.validDigest(pinnedManifestSha256)) {
            throw new IllegalArgumentException("An explicit pinned manifest SHA-256 is required");
        }
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            RdfReleaseVerifier.verify(target, pinnedManifestSha256);
            return destination;
        }
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), ".rdf-fetch-");
        try {
            download.get(immutableBaseUrl.resolve("manifest.json"), staging.resolve("manifest.json"));
            RdfReleaseVerifier.verifyManifest(staging, pinnedManifestSha256);
            for (String name : RdfReleaseVerifier.FILES) {
                download.get(immutableBaseUrl.resolve(name), staging.resolve(name));
            }
            RdfReleaseVerifier.verify(staging, pinnedManifestSha256);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally {
            if (Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        }
    }
}
