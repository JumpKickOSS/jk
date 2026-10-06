// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.ParallelMap;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps one source set's facts index current against its classes directory.
 *
 * <p>One stat per class file decides: every stamp ({@code size:mtime}) matches the header →
 * nothing is read; otherwise every class is extracted, in parallel, and the index rewritten.
 * Restored or branch-switched class files carry new mtimes and so re-extract; the header's body
 * digest is content-derived, so a lane keyed on it sees the same key for the same classes
 * regardless of how they got there. Enabling guards on an already-built module therefore never
 * forces a recompile: the first lane run builds the index.
 *
 * <p>One writer per index: the module lane and the workspace lane both bring the same index up to
 * date from one engine, so {@link #ensure} serialises callers per index file and the one that
 * waited finds the other's fresh index instead of rewriting it.
 */
public final class FactsIndexing {

    public static final String MAIN_INDEX = "main-guard.idx";
    public static final String TEST_INDEX = "test-guard.idx";

    /**
     * One lock per index file for the engine's lifetime; the set of index files an engine touches is
     * bounded by its source sets, so the map never needs sweeping.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> WRITERS = new ConcurrentHashMap<>();

    private FactsIndexing() {}

    /** {@code target/incremental/<set>-guard.idx} beside {@code main-abi.idx}. */
    public static Path indexPath(Path buildDir, String sourceSet) {
        return buildDir.resolve("incremental").resolve(sourceSet + "-guard.idx");
    }

    /** What {@link #ensure} decided. */
    public record Ensured(Path index, String bodyDigest, int classes, Tier tier) {
        public enum Tier {
            /** Every stamp matched; nothing read. */
            FRESH,
            /** A stamp differed or there was no index; every class read and the index rewritten. */
            BUILT,
            /** No classes directory (compile failed or produced nothing). */
            ABSENT
        }
    }

    /**
     * Bring the index at {@code indexFile} up to date with {@code classesDir}. Returns the body
     * digest without loading the class table when nothing changed.
     */
    public static Ensured ensure(Path classesDir, Path indexFile) throws IOException {
        if (!Files.isDirectory(classesDir)) {
            return new Ensured(indexFile, "", 0, Ensured.Tier.ABSENT);
        }
        ReentrantLock writer =
                WRITERS.computeIfAbsent(indexFile.toAbsolutePath().normalize(), k -> new ReentrantLock());
        writer.lock();
        try {
            return ensureLocked(classesDir, indexFile);
        } finally {
            writer.unlock();
        }
    }

    private static Ensured ensureLocked(Path classesDir, Path indexFile) throws IOException {
        Map<String, String> current = stamps(classesDir);
        Optional<FactsFormat.Header> header = FactsFormat.readHeader(indexFile);
        if (header.isPresent() && header.get().stamps().equals(current)) {
            return new Ensured(indexFile, header.get().bodyDigest(), current.size(), Ensured.Tier.FRESH);
        }
        // Every class is extracted again: in parallel that costs less than decoding the old index
        // to keep the unchanged ones.
        Map<String, ClassFacts> classes = new LinkedHashMap<>();
        for (ClassFacts facts : ParallelMap.map(List.copyOf(current.keySet()), rel -> extractOne(classesDir, rel)))
            classes.put(facts.name(), facts);
        FactsFormat.Encoded encoded = FactsFormat.encode(new FactsIndex(classes, current, ""));
        AtomicWrites.replace(indexFile, encoded.bytes());
        return new Ensured(indexFile, encoded.bodyDigest(), classes.size(), Ensured.Tier.BUILT);
    }

    private static ClassFacts extractOne(Path classesDir, String rel) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(classesDir.resolve(rel));
        } catch (IOException e) {
            throw readFailure(rel, classesDir, e, Os.isWindows());
        }
        try {
            return FactsExtractor.extract(bytes);
        } catch (RuntimeException notAClassFile) {
            throw new IOException(
                    "class file " + rel + " in " + classesDir + " does not parse (" + bytes.length
                            + " bytes) — a compile is still writing it, or it is not a class file",
                    notAClassFile);
        }
    }

    /**
     * The body digest when the index at {@code indexFile} is current for {@code classesDir} —
     * every stamp matches — without writing anything. Empty when the index is missing or stale
     * (the lane would re-extract) or the classes directory does not exist. The forecast's probe.
     */
    public static Optional<String> freshDigest(Path classesDir, Path indexFile) throws IOException {
        if (!Files.isDirectory(classesDir)) return Optional.empty();
        Optional<FactsFormat.Header> header = FactsFormat.readHeader(indexFile);
        if (header.isEmpty()) return Optional.empty();
        return header.get().stamps().equals(stamps(classesDir))
                ? Optional.of(header.get().bodyDigest())
                : Optional.empty();
    }

    /** Load the class table; callers hold the {@link Ensured} so the file is known current. */
    public static FactsIndex load(Ensured ensured) throws IOException {
        if (ensured.tier() == Ensured.Tier.ABSENT) return FactsIndex.EMPTY;
        return FactsFormat.read(ensured.index());
    }

    /** {@code relPath → size:mtimeNanos} for every class file, sorted. One stat each, no reads. */
    static Map<String, String> stamps(Path classesDir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path root = classesDir.toAbsolutePath().normalize();
        PathUtil.forEachRegularFile(root, (p, attrs) -> {
            String rel =
                    root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (!rel.endsWith(".class")) return;
            out.put(rel, attrs.size() + ":" + attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        });
        return out;
    }

    /**
     * Why a listed class file would not read. Missing means the classes directory changed under
     * the guard step. On Windows a denial says the same thing: a file deleted while another
     * process holds it open stays listed and refuses to reopen. A POSIX denial is a permission
     * this process does not have — a different fault, and it names itself rather than blaming the
     * step graph.
     */
    static IOException readFailure(String rel, Path classesDir, IOException cause, boolean onWindows) {
        if (cause instanceof NoSuchFileException || (onWindows && cause instanceof AccessDeniedException)) {
            return new IOException(
                    "class file " + rel + " vanished from " + classesDir
                            + " between listing and reading — the classes directory changed under the guard step, "
                            + "so a step that writes it is missing from the step's requires",
                    cause);
        }
        return new IOException(
                cause.getClass().getSimpleName() + " reading class file " + rel + " from " + classesDir, cause);
    }
}
