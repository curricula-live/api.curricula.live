package live.curricula.api.rdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import live.curricula.api.catalog.CatalogUnavailableException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.tdb2.TDB2Factory;

/**
 * Local persistent RDF publication store.
 *
 * Candidate releases load in an isolated TDB2 directory; only fully validated
 * releases are promoted. No public endpoint mutates this store.
 */
public final class RdfReleaseStore implements AutoCloseable {
    public static final String BASE = RdfReleaseVerifier.NAMESPACE;
    private final Path root;
    private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock();
    private Active current;

    public record Status(boolean available, String version, String manifestSha256,
                         String sourceCommit, Map<String, Long> namedGraphs, int concepts) { }
    private record Active(Dataset dataset, VerifiedRelease publication, String sourceCommit) { }

    public RdfReleaseStore(Path storage) throws IOException {
        root = storage.toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("releases"));
        Path pointer = root.resolve("active.txt");
        if (Files.isRegularFile(pointer)) {
            activatePreviouslyVerified(Files.readString(pointer).trim());
        }
    }

    public boolean available() {
        mutex.readLock().lock();
        try { return current != null; }
        finally { mutex.readLock().unlock(); }
    }

    public Status status() {
        mutex.readLock().lock();
        try {
            if (current == null) return new Status(false, null, null, null, Map.of(), 0);
            return new Status(true, current.publication.version(),
                    current.publication.manifestSha256(), current.sourceCommit,
                    current.publication.graphCounts(), current.publication.concepts());
        } finally { mutex.readLock().unlock(); }
    }

    /** Runs a read-only transaction, preventing activation from closing the active dataset. */
    public <T> T read(Function<Dataset, T> query) {
        mutex.readLock().lock();
        try {
            if (current == null) throw new CatalogUnavailableException();
            Dataset dataset = current.dataset;
            dataset.begin(ReadWrite.READ);
            try { return query.apply(dataset); }
            finally { dataset.end(); }
        } finally { mutex.readLock().unlock(); }
    }

    /**
     * Validates a pinned release and atomically activates its immutable TDB2 image.
     * A candidate error never changes the running dataset or the active pointer.
     */
    public Status ingest(Path packageDir, String pinnedManifestSha256, String sourceCommit)
            throws IOException {
        if (sourceCommit == null || !sourceCommit.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("A full immutable source commit SHA is required");
        }
        VerifiedRelease publication = RdfReleaseVerifier.verify(packageDir, pinnedManifestSha256);
        Path releaseDir = root.resolve("releases").resolve(publication.manifestSha256());
        if (!Files.isDirectory(releaseDir)) {
            Path staging = root.resolve("releases").resolve(".staging-" + UUID.randomUUID());
            Files.createDirectories(staging);
            try {
                Dataset dataset = TDB2Factory.connectDataset(staging.resolve("tdb2").toString());
                try {
                    dataset.begin(ReadWrite.WRITE);
                    try (InputStream input = Files.newInputStream(packageDir.resolve("formal-sciences.nq"))) {
                        try {
                            RDFDataMgr.read(dataset, input, Lang.NQUADS);
                            dataset.commit();
                        } finally { dataset.end(); }
                    }
                    verifyStoredDataset(dataset, publication, packageDir);
                } finally { dataset.close(); }
                Files.copy(packageDir.resolve("manifest.json"), staging.resolve("manifest.json"));
                Files.writeString(staging.resolve("source-commit.txt"), sourceCommit);
                // Copy closed TDB2 files into a new inode set before activation.
                // Jena retains a process-lock registry keyed by the staging location;
                // renaming the same locked files would overlap locks on reopen.
                Path prepared = root.resolve("releases").resolve(".prepared-" + UUID.randomUUID());
                try {
                    copyTree(staging, prepared);
                    promoteDirectory(prepared, releaseDir);
                } finally {
                    if (Files.exists(prepared)) deleteTree(prepared);
                }
            } finally {
                if (Files.exists(staging)) deleteTree(staging);
            }
        }
        switchTo(publication.manifestSha256());
        return status();
    }

    public Status rollback() throws IOException {
        Path previous = root.resolve("previous.txt");
        if (!Files.isRegularFile(previous)) throw new IOException("No previous release is recorded");
        switchTo(Files.readString(previous).trim());
        return status();
    }

    private void activatePreviouslyVerified(String digest) throws IOException {
        Active opened = openVerified(digest);
        current = opened;
    }

    private Active openVerified(String digest) throws IOException {
        if (!digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid release digest pointer");
        Path release = root.resolve("releases").resolve(digest);
        // Manifest bytes remain immutable; the original package artifacts are not required to restart.
        if (!RdfReleaseVerifier.sha256(release.resolve("manifest.json")).equals(digest)) {
            throw new IOException("Stored release manifest changed");
        }
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var manifest = mapper.readTree(Files.readString(release.resolve("manifest.json")));
        if (!RdfReleaseVerifier.CONTRACT.equals(manifest.path("contract").asText()) ||
                !manifest.path("conforms").asBoolean(false)) {
            throw new IOException("Stored RDF manifest incompatible");
        }
        var counts = new LinkedHashMap<String, Long>();
        for (String graph : RdfReleaseVerifier.GRAPHS) {
            long count = manifest.path("counts").path(graph).asLong(-1);
            if (count < 0) throw new IOException("Invalid stored graph count");
            counts.put(graph, count);
        }
        var verified = new VerifiedRelease(release, manifest.path("version").asText(),
                manifest.path("namespace").asText(), digest, Map.copyOf(counts),
                manifest.path("concepts").asInt());
        Dataset dataset = TDB2Factory.connectDataset(release.resolve("tdb2").toString());
        try {
            verifyStoredDataset(dataset, verified, null);
            return new Active(dataset, verified, Files.readString(release.resolve("source-commit.txt")).trim());
        } catch (Exception e) {
            dataset.close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Stored RDF data is invalid", e);
        }
    }

    private static void verifyStoredDataset(Dataset dataset, VerifiedRelease release, Path sourceDir)
            throws IOException {
        dataset.begin(ReadWrite.READ);
        try {
            if (!dataset.getDefaultModel().isEmpty()) {
                throw new IOException("The default RDF graph must be empty");
            }
            var actualNames = new java.util.HashSet<String>();
            dataset.listNames().forEachRemaining(actualNames::add);
            if (actualNames.size() != RdfReleaseVerifier.GRAPHS.size()) {
                throw new IOException("Unexpected named-graph set: " + actualNames);
            }
            for (String name : RdfReleaseVerifier.GRAPHS) {
                String iri = release.namespace() + "graph/" + name;
                if (!actualNames.contains(iri) ||
                    dataset.getNamedModel(iri).size() != release.graphCounts().get(name)) {
                    throw new IOException("Graph count/name mismatch: " + name);
                }
            }
            if (sourceDir != null) {
                Dataset trig = DatasetFactory.createTxnMem();
                Dataset align = DatasetFactory.createTxnMem();
                try {
                    try (InputStream input = Files.newInputStream(sourceDir.resolve("formal-sciences.trig"))) {
                        RDFDataMgr.read(trig, input, Lang.TRIG);
                    }
                    try (InputStream input = Files.newInputStream(sourceDir.resolve("source-alignments.trig"))) {
                        RDFDataMgr.read(align, input, Lang.TRIG);
                    }
                    if (!trig.getDefaultModel().isEmpty() || !align.getDefaultModel().isEmpty()) {
                        throw new IOException("TriG distributions must have empty default graph");
                    }
                    for (String name : RdfReleaseVerifier.GRAPHS) {
                        String iri = release.namespace() + "graph/" + name;
                        if (!dataset.getNamedModel(iri).isIsomorphicWith(trig.getNamedModel(iri))) {
                            throw new IOException("TriG/N-Quads semantic mismatch: " + name);
                        }
                    }
                    String alignment = release.namespace() + "graph/alignments";
                    if (!dataset.getNamedModel(alignment).isIsomorphicWith(align.getNamedModel(alignment))) {
                        throw new IOException("Alignment distribution mismatch");
                    }
                } finally { trig.close(); align.close(); }
                var asserted = dataset.getNamedModel(release.namespace() + "graph/asserted");
                try (InputStream input = Files.newInputStream(sourceDir.resolve("formal-sciences.ttl"))) {
                    var ttl = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                    RDFDataMgr.read(ttl, input, Lang.TURTLE);
                    if (!ttl.isIsomorphicWith(asserted)) {
                        throw new IOException("Turtle asserted graph mismatch");
                    }
                }
                long conceptCount = asserted.listStatements(null,
                        org.apache.jena.vocabulary.RDF.type,
                        org.apache.jena.vocabulary.SKOS.Concept).toList().size();
                if (conceptCount != release.concepts()) {
                    throw new IOException("Canonical concept count mismatch");
                }
            }
        } finally { dataset.end(); }
    }

    private void switchTo(String digest) throws IOException {
        mutex.writeLock().lock();
        try {
            if (current != null && current.publication.manifestSha256().equals(digest)) return;
            Active candidate = openVerified(digest);
            try {
                if (current != null) writePointer("previous.txt", current.publication.manifestSha256());
                writePointer("active.txt", digest);
                Active old = current;
                current = candidate;
                if (old != null) old.dataset.close();
            } catch (IOException e) {
                candidate.dataset.close();
                throw e;
            }
        } finally { mutex.writeLock().unlock(); }
    }

    private void writePointer(String name, String value) throws IOException {
        Path temp = root.resolve(name + ".tmp");
        Files.writeString(temp, value + "\n");
        try {
            Files.move(temp, root.resolve(name),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path source : paths.toList()) {
                Path target = to.resolve(from.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    private static void promoteDirectory(Path staging, Path release) throws IOException {
        try { Files.move(staging, release, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(staging, release); }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Override public void close() {
        mutex.writeLock().lock();
        try {
            if (current != null) {
                current.dataset.close();
                current = null;
            }
        } finally { mutex.writeLock().unlock(); }
    }
}
