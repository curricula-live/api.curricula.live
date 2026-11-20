package live.curricula.api.rdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import live.curricula.api.catalog.CatalogUnavailableException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFParserBuilder;
import org.apache.jena.riot.RiotException;
import org.apache.jena.sparql.util.IsoMatcher;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

/** Persistent, exclusively owned TDB2 store with immutable releases and a single activation record. */
public final class RdfReleaseStore implements AutoCloseable {
    public static final String BASE = RdfReleaseVerifier.NAMESPACE;
    private static final String STATE_FILE = "release-state";
    private static final String STAGING = ".staging-";
    private static final String PREPARED = ".prepared-";
    private static final String STATE_TEMP = ".release-state-";
    private static final String ABSENT = "-";
    private static final Set<Path> OWNED_ROOTS = ConcurrentHashMap.newKeySet();
    private static final System.Logger LOG = System.getLogger(RdfReleaseStore.class.getName());

    private final Path root;
    private final Path releases;
    private final FileChannel ownership;
    private final TransitionObserver transitions;
    private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock();
    private Active current;
    private Selection selection;
    private boolean closed;

    public record Status(boolean available, String version, String manifestSha256,
                         String sourceCommit, Map<String, Long> namedGraphs, int concepts) { }

    private record Active(Dataset dataset, VerifiedRelease publication, String sourceCommit) { }
    private record Selection(String active, String previous) { }

    enum Transition { RELEASE_PROMOTED, BEFORE_SELECTION_REPLACE, AFTER_SELECTION_REPLACE }

    @FunctionalInterface
    interface TransitionObserver {
        void reached(Transition transition) throws IOException;
    }

    public RdfReleaseStore(Path storage) throws IOException {
        this(storage, transition -> { });
    }

    RdfReleaseStore(Path storage, TransitionObserver transitions) throws IOException {
        root = Files.createDirectories(storage).toRealPath();
        releases = root.resolve("releases");
        this.transitions = transitions;
        ownership = acquireOwnership(root);
        try {
            Files.createDirectories(releases);
            cleanupInterruptedWrites();
            selection = readSelection();
            if (selection != null) current = openVerified(selection.active());
        } catch (IOException | RuntimeException | Error e) {
            try {
                releaseOwnership();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static FileChannel acquireOwnership(Path root) throws IOException {
        // Closing a second channel on the same file can release the JVM's first
        // lock on some filesystems. Reject duplicate local owners before opening it.
        if (!OWNED_ROOTS.add(root)) throw new IOException("RDF store is already open: " + root);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(root.resolve(".store.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (channel.tryLock() == null) throw new IOException("RDF store is already open: " + root);
            return channel;
        } catch (IOException | RuntimeException | Error e) {
            try {
                if (channel != null) channel.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            } finally {
                OWNED_ROOTS.remove(root);
            }
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("RDF store is already open: " + root, e);
            }
            throw e;
        }
    }

    private void releaseOwnership() throws IOException {
        try {
            ownership.close();
        } finally {
            OWNED_ROOTS.remove(root);
        }
    }

    public boolean available() {
        return status().available();
    }

    public Status status() {
        mutex.readLock().lock();
        try {
            if (current == null) return new Status(false, null, null, null, Map.of(), 0);
            var publication = current.publication();
            return new Status(true, publication.version(), publication.manifestSha256(),
                    current.sourceCommit(), publication.graphCounts(), publication.concepts());
        } finally {
            mutex.readLock().unlock();
        }
    }

    /** Runs a read transaction while preventing activation from closing its dataset. */
    public <T> T read(Function<Dataset, T> query) {
        mutex.readLock().lock();
        try {
            if (current == null) throw new CatalogUnavailableException();
            Dataset dataset = current.dataset();
            dataset.begin(ReadWrite.READ);
            try {
                return query.apply(dataset);
            } finally {
                dataset.end();
            }
        } finally {
            mutex.readLock().unlock();
        }
    }

    /**
     * Validates a private copy of the publication before ingesting it. Only the short
     * activation step blocks reads of the current release.
     */
    public synchronized Status ingest(Path packageDir, String pinnedManifestSha256, String sourceCommit)
            throws IOException {
        ensureOpen();
        if (!RdfReleaseVerifier.validCommit(sourceCommit)) {
            throw new IllegalArgumentException("A full immutable source commit SHA is required");
        }
        RdfReleaseVerifier.verifyManifest(packageDir, pinnedManifestSha256);
        Path staged = Files.createTempDirectory(releases, STAGING);
        try {
            Path snapshot = Files.createDirectory(staged.resolve("package"));
            Files.copy(RdfReleaseVerifier.regularFile(packageDir, "manifest.json"), snapshot.resolve("manifest.json"));
            for (String name : RdfReleaseVerifier.FILES) {
                Files.copy(RdfReleaseVerifier.regularFile(packageDir, name), snapshot.resolve(name));
            }
            VerifiedRelease publication = RdfReleaseVerifier.verify(snapshot, pinnedManifestSha256);
            Path releaseDir = releases.resolve(publication.manifestSha256());
            if (!Files.exists(releaseDir)) {
                prepareRelease(staged, publication, sourceCommit.toLowerCase(Locale.ROOT), releaseDir);
            }
            switchTo(publication.manifestSha256());
            return status();
        } finally {
            if (Files.exists(staged)) deleteTree(staged);
        }
    }

    public synchronized Status rollback() throws IOException {
        ensureOpen();
        if (selection == null || selection.previous() == null) {
            throw new IOException("No previous release is recorded");
        }
        switchTo(selection.previous());
        return status();
    }

    private void prepareRelease(Path staged, VerifiedRelease publication, String sourceCommit,
                                Path releaseDir) throws IOException {
        Path stageDb = staged.resolve("tdb2");
        Dataset dataset = TDB2Factory.connectDataset(stageDb.toString());
        try {
            dataset.begin(ReadWrite.WRITE);
            try {
                parse(publication.packageDir().resolve("formal-sciences.nq"), Lang.NQUADS).parse(dataset);
                dataset.commit();
            } finally {
                dataset.end();
            }
            dataset.begin(ReadWrite.READ);
            try {
                verifyStoredDataset(dataset, publication);
                verifyEquivalentDistributions(dataset, publication);
            } finally {
                dataset.end();
            }
        } catch (RiotException e) {
            throw new IOException("Invalid RDF publication data", e);
        } finally {
            releaseDataset(dataset);
        }

        Files.move(publication.packageDir().resolve("manifest.json"), staged.resolve("manifest.json"));
        deleteTree(publication.packageDir());
        Files.writeString(staged.resolve("source-commit.txt"), sourceCommit);
        forceTree(staged);
        Files.move(staged, releaseDir, StandardCopyOption.ATOMIC_MOVE);
        force(releases, StandardOpenOption.READ);
        transitions.reached(Transition.RELEASE_PROMOTED);
    }

    private Selection readSelection() throws IOException {
        Path state = root.resolve(STATE_FILE);
        if (Files.exists(state)) {
            List<String> lines = Files.readAllLines(state);
            if (lines.size() != 2) throw new IOException("Invalid RDF release selection");
            String active = checkDigest(lines.getFirst());
            String previous = ABSENT.equals(lines.getLast()) ? null : checkDigest(lines.getLast());
            if (active.equals(previous)) throw new IOException("Invalid RDF release selection");
            return new Selection(active, previous);
        }

        // Read the old format until the first successful activation. An old
        // interrupted write could leave previous.txt pointing at active.txt.
        Path legacyActive = root.resolve("active.txt");
        if (!Files.exists(legacyActive)) return null;
        String active = checkDigest(Files.readString(legacyActive).trim());
        Path legacyPrevious = root.resolve("previous.txt");
        String previous = Files.exists(legacyPrevious)
                ? checkDigest(Files.readString(legacyPrevious).trim()) : null;
        return new Selection(active, active.equals(previous) ? null : previous);
    }

    private static String checkDigest(String digest) throws IOException {
        if (!RdfReleaseVerifier.validDigest(digest)) throw new IOException("Invalid release digest pointer");
        return digest;
    }

    private Active openVerified(String digest) throws IOException {
        Path release = releases.resolve(checkDigest(digest));
        VerifiedRelease verified = RdfReleaseVerifier.verifyManifest(release, digest);
        String commit = Files.readString(RdfReleaseVerifier.regularFile(release, "source-commit.txt")).trim();
        if (!RdfReleaseVerifier.validCommit(commit) || !Files.isDirectory(release.resolve("tdb2"))) {
            throw new IOException("Invalid stored RDF release metadata");
        }
        Dataset dataset = TDB2Factory.connectDataset(release.resolve("tdb2").toString());
        try {
            dataset.begin(ReadWrite.READ);
            try {
                verifyStoredDataset(dataset, verified);
            } finally {
                dataset.end();
            }
            return new Active(dataset, verified, commit);
        } catch (IOException | RuntimeException e) {
            closeDataset(dataset);
            if (e instanceof IOException io) throw io;
            throw new IOException("Stored RDF data is invalid", e);
        }
    }

    private static void verifyStoredDataset(Dataset dataset, VerifiedRelease release) throws IOException {
        Set<String> expected = new HashSet<>();
        for (String name : RdfReleaseVerifier.GRAPHS) {
            String iri = release.namespace() + "graph/" + name;
            expected.add(iri);
            if (dataset.getNamedModel(iri).size() != release.graphCounts().get(name)) {
                throw new IOException("Graph count mismatch: " + name);
            }
        }
        verifyGraphNames(dataset, expected);
        var asserted = dataset.getNamedModel(release.namespace() + "graph/asserted");
        var concepts = asserted.listResourcesWithProperty(RDF.type, SKOS.Concept);
        long count = 0;
        try {
            while (concepts.hasNext()) {
                var concept = concepts.nextResource();
                if (!concept.isURIResource() || !concept.getURI().startsWith(release.namespace() + "concept/")) {
                    throw new IOException("Concept outside the canonical namespace");
                }
                count++;
            }
        } finally {
            concepts.close();
        }
        if (count != release.concepts()) throw new IOException("Canonical concept count mismatch");
    }

    private static void verifyGraphNames(Dataset dataset, Set<String> expected) throws IOException {
        Set<String> actual = new HashSet<>();
        dataset.listNames().forEachRemaining(actual::add);
        if (!dataset.getDefaultModel().isEmpty() || !actual.equals(expected)) {
            throw new IOException("Unexpected named or default RDF graph: " + actual);
        }
    }

    private static void verifyEquivalentDistributions(Dataset dataset, VerifiedRelease release) throws IOException {
        Path dir = release.packageDir();
        Dataset trig = DatasetFactory.createTxnMem();
        Dataset alignments = DatasetFactory.createTxnMem();
        var turtle = ModelFactory.createDefaultModel();
        try {
            parse(dir.resolve("formal-sciences.trig"), Lang.TRIG).parse(trig);
            if (!IsoMatcher.isomorphic(dataset.asDatasetGraph(), trig.asDatasetGraph())) {
                throw new IOException("TriG/N-Quads semantic mismatch");
            }

            String alignment = release.namespace() + "graph/alignments";
            parse(dir.resolve("source-alignments.trig"), Lang.TRIG).parse(alignments);
            verifyGraphNames(alignments, Set.of(alignment));
            if (!dataset.getNamedModel(alignment).isIsomorphicWith(alignments.getNamedModel(alignment))) {
                throw new IOException("Alignment distribution mismatch");
            }
            parse(dir.resolve("formal-sciences.ttl"), Lang.TURTLE).parse(turtle);
            if (!turtle.isIsomorphicWith(dataset.getNamedModel(release.namespace() + "graph/asserted"))) {
                throw new IOException("Turtle asserted graph mismatch");
            }
        } finally {
            trig.close();
            alignments.close();
            turtle.close();
        }
    }

    private static RDFParserBuilder parse(Path path, Lang lang) {
        return RDFParser.source(path).lang(lang).strict(true).checking(true);
    }

    private void switchTo(String digest) throws IOException {
        mutex.writeLock().lock();
        try {
            if (selection != null && selection.active().equals(digest)) return;
            Active candidate = openVerified(digest);
            Active old = current;
            var next = new Selection(digest, selection == null ? null : selection.active());
            Path temp = null;
            try {
                temp = Files.createTempFile(root, STATE_TEMP, ".tmp");
                Files.writeString(temp, next.active() + "\n" + (next.previous() == null ? ABSENT : next.previous()) + "\n");
                force(temp, StandardOpenOption.WRITE);
                transitions.reached(Transition.BEFORE_SELECTION_REPLACE);
                Files.move(temp, root.resolve(STATE_FILE),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                // Rename is the commit point. Even if fsync subsequently fails,
                // the running readers must agree with the visible selection.
                current = candidate;
                selection = next;
                transitions.reached(Transition.AFTER_SELECTION_REPLACE);
                force(root, StandardOpenOption.READ);
            } finally {
                Active detached = current == candidate ? old : candidate;
                if (detached != null) closeDataset(detached.dataset());
                if (temp != null) Files.deleteIfExists(temp);
            }
        } finally {
            mutex.writeLock().unlock();
        }
    }

    private void cleanupInterruptedWrites() throws IOException {
        try (var children = Files.list(releases)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(STAGING) || name.startsWith(PREPARED)) deleteTree(child);
            }
        }
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (child.getFileName().toString().startsWith(STATE_TEMP)) Files.deleteIfExists(child);
            }
        }
    }

    private static void forceTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                force(path, Files.isDirectory(path) ? StandardOpenOption.READ : StandardOpenOption.WRITE);
            }
        }
    }

    private static void force(Path path, StandardOpenOption mode) throws IOException {
        try (var file = FileChannel.open(path, mode)) {
            file.force(true);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("RDF store is closed");
    }

    private static void releaseDataset(Dataset dataset) {
        // TDB2's switchable graph close is a no-op. Release both of Jena's
        // cached locations after draining all transactions under our locks.
        TDBInternal.expel(dataset.asDatasetGraph());
    }

    private static void closeDataset(Dataset dataset) {
        try {
            releaseDataset(dataset);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not close detached RDF dataset", failure);
        }
    }

    @Override
    public synchronized void close() {
        mutex.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            if (current != null) {
                closeDataset(current.dataset());
                current = null;
            }
            releaseOwnership();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not release RDF store ownership", e);
        } finally {
            mutex.writeLock().unlock();
        }
    }
}
