// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one source set's facts index current against its classes directory.
 *
 * <p>Three cost tiers, decided by one stat per class file: every stamp ({@code size:mtime}) matches
 * the header → nothing is read; some differ → only those classes are re-extracted and the index
 * rewritten; no index → a cold walk. Restored or branch-switched class files carry new mtimes and
 * so re-extract; the header's body digest is content-derived, so a lane keyed on it sees the same
 * key for the same classes regardless of how they got there. Enabling guards on an already-built
 * module therefore never forces a recompile: the first lane run builds the index cold.
 */
public final class FactsIndexing {

    public static final String MAIN_INDEX = "main-guard.idx";
    public static final String TEST_INDEX = "test-guard.idx";

    private FactsIndexing() {}

    /** {@code target/incremental/<set>-guard.idx} beside {@code main-abi.idx}. */
    public static Path indexPath(Path buildDir, String sourceSet) {
        return buildDir.resolve("incremental").resolve(sourceSet + "-guard.idx");
    }

    /** What {@link #ensure} decided. */
    public record Ensured(Path index, String bodyDigest, int classes, int reextracted, Tier tier) {
        public enum Tier {
            /** Every stamp matched; nothing read. */
            FRESH,
            /** Some classes changed; those were re-read. */
            INCREMENTAL,
            /** No usable index; every class read. */
            COLD,
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
            return new Ensured(indexFile, "", 0, 0, Ensured.Tier.ABSENT);
        }
        Map<String, String> current = stamps(classesDir);
        Optional<FactsFormat.Header> header = FactsFormat.readHeader(indexFile);
        if (header.isPresent() && header.get().stamps().equals(current)) {
            return new Ensured(indexFile, header.get().bodyDigest(), current.size(), 0, Ensured.Tier.FRESH);
        }
        Map<String, ClassFacts> classes = new LinkedHashMap<>();
        Map<String, String> previous = Map.of();
        if (header.isPresent()) {
            FactsIndex old = FactsFormat.read(indexFile);
            previous = old.stamps();
            for (var e : old.classes().entrySet()) {
                String rel = e.getKey() + ".class";
                if (current.containsKey(rel) && current.get(rel).equals(previous.get(rel)))
                    classes.put(e.getKey(), e.getValue());
            }
        }
        int reextracted = 0;
        for (String rel : current.keySet()) {
            String internal = rel.substring(0, rel.length() - ".class".length());
            if (classes.containsKey(internal)) continue;
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(classesDir.resolve(rel));
            } catch (NoSuchFileException e) {
                throw new IOException(
                        "class file " + rel + " vanished from " + classesDir
                                + " between listing and reading — the classes directory changed under the guard step, "
                                + "so a step that writes it is missing from the step's requires",
                        e);
            }
            ClassFacts facts = FactsExtractor.extract(bytes);
            classes.put(facts.name(), facts);
            reextracted++;
        }
        FactsIndex built = new FactsIndex(classes, current, "");
        String digest = FactsFormat.digestOf(built);
        FactsIndex stamped = built.withStamps(current, digest);
        FactsFormat.write(indexFile, stamped);
        Ensured.Tier tier = header.isPresent() ? Ensured.Tier.INCREMENTAL : Ensured.Tier.COLD;
        return new Ensured(indexFile, digest, classes.size(), reextracted, tier);
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
}
