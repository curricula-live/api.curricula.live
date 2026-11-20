package live.curricula.api.rdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class RdfReleaseVerifier {
    public static final String CONTRACT = "curricula.rdf-release/v1";
    public static final String NAMESPACE = "https://curricula.live/id/formal-sciences/";
    public static final List<String> FILES = List.of(
            "formal-sciences.ttl", "formal-sciences.trig", "formal-sciences.nq",
            "source-alignments.trig", "validation-report.json");
    public static final List<String> GRAPHS = List.of(
            "asserted", "inferred", "alignments", "provenance");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RdfReleaseVerifier() { }

    public static VerifiedRelease verify(Path root, String pinnedManifestSha256) throws IOException {
        if (pinnedManifestSha256 == null || !pinnedManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("An explicit pinned manifest SHA-256 is required");
        }
        root = root.toRealPath();
        Path manifestPath = root.resolve("manifest.json");
        if (!sha256(manifestPath).equals(pinnedManifestSha256)) {
            throw new IOException("Pinned manifest SHA-256 mismatch");
        }
        JsonNode manifest = JSON.readTree(Files.readString(manifestPath));
        if (!CONTRACT.equals(manifest.path("contract").asText())) {
            throw new IOException("Incompatible RDF release contract");
        }
        if (!NAMESPACE.equals(manifest.path("namespace").asText())) {
            throw new IOException("Unexpected canonical RDF namespace");
        }
        if (!manifest.path("conforms").asBoolean(false)) {
            throw new IOException("Ontology manifest does not conform");
        }
        JsonNode artifacts = manifest.path("artifacts");
        if (artifacts.size() != FILES.size()) {
            throw new IOException("Unexpected artifact roster");
        }
        for (String name : FILES) {
            String expected = artifacts.path(name).asText();
            if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha256(root.resolve(name)))) {
                throw new IOException("Missing, invalid, or modified artifact: " + name);
            }
        }
        JsonNode report = JSON.readTree(Files.readString(root.resolve("validation-report.json")));
        if (!report.path("conforms").asBoolean(false)) {
            throw new IOException("Validation report does not conform");
        }
        var counts = new LinkedHashMap<String, Long>();
        JsonNode graphCounts = manifest.path("counts");
        if (graphCounts.size() != GRAPHS.size()) {
            throw new IOException("Unexpected named-graph roster");
        }
        for (String graph : GRAPHS) {
            long n = graphCounts.path(graph).asLong(-1);
            if (n < 0) throw new IOException("Invalid graph count: " + graph);
            counts.put(graph, n);
        }
        int concepts = manifest.path("concepts").asInt(-1);
        if (concepts < 0) throw new IOException("Invalid concept count");
        String version = manifest.path("version").asText();
        if (version.isBlank()) throw new IOException("Missing ontology version");
        return new VerifiedRelease(root, version, NAMESPACE,
                pinnedManifestSha256, Map.copyOf(counts), concepts);
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM lacks SHA-256", ex);
        }
    }
}
