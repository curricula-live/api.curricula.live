package live.curricula.api.rdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
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
        return verify(root, pinnedManifestSha256, RdfOntologyRegistry.FORMAL_SCIENCES);
    }

    static List<String> files(RdfOntologyRegistry.Producer producer) {
        return List.of(producer.id() + ".ttl", producer.id() + ".trig", producer.id() + ".nq",
                "source-alignments.trig", "validation-report.json");
    }

    static VerifiedRelease verify(Path root, String pinnedManifestSha256, RdfOntologyRegistry.Producer producer)
            throws IOException {
        JsonNode manifest = pinnedManifest(root, pinnedManifestSha256);
        VerifiedRelease release = parseManifest(root, pinnedManifestSha256, manifest, producer);
        JsonNode artifacts = manifest.path("artifacts");
        for (String name : files(producer)) {
            if (!artifacts.path(name).asText().equals(sha256(regularFile(root, name)))) {
                throw new IOException("Modified artifact: " + name);
            }
        }
        JsonNode report = readJson(regularFile(root, "validation-report.json"));
        if (!conforms(report)) {
            throw new IOException("Validation report does not conform");
        }
        return release;
    }

    static VerifiedRelease verifyManifest(Path root, String pinnedManifestSha256) throws IOException {
        return verifyManifest(root, pinnedManifestSha256, RdfOntologyRegistry.FORMAL_SCIENCES);
    }

    static VerifiedRelease verifyManifest(Path root, String pinnedManifestSha256, RdfOntologyRegistry.Producer producer)
            throws IOException {
        return parseManifest(root, pinnedManifestSha256, pinnedManifest(root, pinnedManifestSha256), producer);
    }

    private static JsonNode pinnedManifest(Path root, String pinnedManifestSha256) throws IOException {
        if (!validDigest(pinnedManifestSha256)) {
            throw new IllegalArgumentException("An explicit pinned manifest SHA-256 is required");
        }
        byte[] bytes = Files.readAllBytes(regularFile(root, "manifest.json"));
        if (!HexFormat.of().formatHex(sha256Digest().digest(bytes)).equals(pinnedManifestSha256)) {
            throw new IOException("Pinned manifest SHA-256 mismatch");
        }
        return parseJson(bytes);
    }

    private static VerifiedRelease parseManifest(Path root, String digest, JsonNode manifest, RdfOntologyRegistry.Producer producer) throws IOException {
        if (!CONTRACT.equals(manifest.path("contract").asText())) {
            throw new IOException("Incompatible RDF release contract");
        }
        if (!producer.namespace().equals(manifest.path("namespace").asText())) {
            throw new IOException("Unexpected canonical RDF namespace");
        }
        if (!conforms(manifest)) {
            throw new IOException("Ontology manifest does not conform");
        }

        JsonNode artifacts = manifest.path("artifacts");
        if (!artifacts.isObject() || artifacts.size() != files(producer).size()) {
            throw new IOException("Unexpected artifact roster");
        }
        for (String name : files(producer)) {
            if (!artifacts.path(name).isString() || !validDigest(artifacts.path(name).asText())) {
                throw new IOException("Invalid artifact digest: " + name);
            }
        }

        JsonNode graphCounts = manifest.path("counts");
        if (!graphCounts.isObject() || graphCounts.size() != GRAPHS.size()) {
            throw new IOException("Unexpected named-graph roster");
        }
        var counts = new LinkedHashMap<String, Long>();
        for (String graph : GRAPHS) {
            JsonNode n = graphCounts.path(graph);
            if (!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < 0) {
                throw new IOException("Invalid graph count: " + graph);
            }
            counts.put(graph, n.asLong());
        }
        JsonNode concepts = manifest.path("concepts");
        if (!concepts.isIntegralNumber() || !concepts.canConvertToInt() || concepts.asInt() < 0) {
            throw new IOException("Invalid concept count");
        }
        JsonNode version = manifest.path("version");
        if (!version.isString() || version.asText().isBlank()) throw new IOException("Missing ontology version");
        return new VerifiedRelease(root, version.asText(), producer.namespace(),
                digest, Map.copyOf(counts), concepts.asInt());
    }

    private static boolean conforms(JsonNode node) {
        JsonNode conforms = node.path("conforms");
        return conforms.isBoolean() && conforms.asBoolean();
    }

    private static JsonNode readJson(Path path) throws IOException {
        return parseJson(Files.readAllBytes(path));
    }

    private static JsonNode parseJson(byte[] bytes) throws IOException {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (parsed == null || !parsed.isObject()) throw new IOException("Expected an RDF metadata object");
            return parsed;
        } catch (JacksonException e) {
            throw new IOException("Invalid RDF publication JSON", e);
        }
    }

    static Path regularFile(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or non-regular RDF publication file: " + name);
        }
        return file;
    }

    static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static boolean validCommit(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM lacks SHA-256", ex);
        }
    }
}
