package live.curricula.api.rdf;

import java.nio.file.Path;
import java.util.Map;

/** Immutable metadata for a checksummed, independently published RDF release. */
public record VerifiedRelease(Path packageDir, String version, String namespace,
                              String manifestSha256, Map<String, Long> graphCounts,
                              int concepts) { }
