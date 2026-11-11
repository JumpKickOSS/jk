// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.MirroredOutputs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The main resource copies an annotation processor rewrote: {@code
 * target/incremental/processed-resources.txt}, one tree-relative path and the content sha of the
 * resource it was seeded from per line.
 *
 * <p>Resources are copied into the classes tree before compile-main, so a processor reads a copy
 * through the Filer and may write that path back — Helidon's codegen merges the static {@code
 * META-INF/helidon/manifest} into the one it writes. The file there is then the compile's output:
 * {@link ResourceMirror#syncMain} leaves it while its resource is unchanged, and the compile's
 * record keeps it ({@link MirroredOutputs#without}). Settled after every compile-main that ran.
 */
public final class ProcessedResources {

    private ProcessedResources() {}

    /** The ledger's rows: tree-relative path to the content sha of the resource it was seeded from. */
    public static Map<String, String> read(Path incremental) throws IOException {
        Path ledger = incremental.resolve(MirroredOutputs.PROCESSED_LEDGER);
        if (!Files.isRegularFile(ledger)) return Map.of();
        Map<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            int tab = line.lastIndexOf('\t');
            if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1).strip());
        }
        return out;
    }

    /**
     * Rewrite the ledger after compile-main ran — compiled or restored — over {@code classes}.
     * {@code processors} is whether the compile could run any; without them no copy is a
     * processor's, and each the ledger held goes back to its resource. {@code restored} is the
     * record a cache hit laid down: a held copy it does not hold was not written by the compile it
     * restores, so it goes back to its resource too. A copy is then held when its bytes differ
     * from its resource's; the size and mtime a plain copy carries over settle most without a read.
     */
    public static void settle(Path classes, Path incremental, boolean processors, @Nullable Set<String> restored)
            throws IOException {
        Map<String, Path> sources = MirroredOutputs.sources(incremental);
        for (String rel : read(incremental).keySet()) {
            Path source = sources.get(rel);
            if (source != null && (!processors || (restored != null && !restored.contains(rel)))) {
                reset(source, classes.resolve(rel));
            }
        }
        Map<String, String> held = new TreeMap<>();
        if (processors) {
            for (var e : sources.entrySet()) {
                String sha = rewrittenFrom(e.getValue(), classes.resolve(e.getKey()));
                if (sha != null) held.put(e.getKey(), sha);
            }
        }
        write(incremental, held);
    }

    /**
     * {@link #settle} after compile-main returned: {@code processors} as there, and a cache hit
     * ({@code hitKey} non-null) settled against the record it restored.
     */
    public static void afterCompile(
            Path classes, Path incremental, boolean processors, ActionCache cache, @Nullable String hitKey)
            throws IOException {
        Set<String> restored = null;
        if (hitKey != null) {
            restored = cache.lookup(hitKey).map(r -> r.outputs().keySet()).orElse(Set.of());
        }
        settle(classes, incremental, processors, restored);
    }

    /**
     * The held copies under {@code classes} whose resource still has the content sha they were
     * seeded from: the copy leaves them, so the forecast does not read them as drift.
     */
    public static Set<String> unchanged(Path classes) throws IOException {
        Path target = classes.toAbsolutePath().normalize().getParent();
        if (target == null) return Set.of();
        Path incremental = target.resolve("incremental");
        Map<String, String> held = read(incremental);
        if (held.isEmpty()) return Set.of();
        Map<String, Path> sources = MirroredOutputs.sources(incremental);
        Set<String> out = new TreeSet<>();
        for (var e : held.entrySet()) {
            Path source = sources.get(e.getKey());
            if (source == null || !Files.isRegularFile(source)) continue;
            if (FileHashMemo.contentHash(source.toAbsolutePath().normalize()).equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Forget every held copy: the tree they were in is gone, and the copy lays each resource down again. */
    public static void clear(Path incremental) throws IOException {
        Files.deleteIfExists(incremental.resolve(MirroredOutputs.PROCESSED_LEDGER));
    }

    /** The resource's content sha when {@code copy} exists and its bytes are not {@code source}'s; else null. */
    private static @Nullable String rewrittenFrom(Path source, Path copy) throws IOException {
        Optional<BasicFileAttributes> dst = PathUtil.stat(copy);
        Optional<BasicFileAttributes> src = PathUtil.stat(source);
        if (dst.isEmpty()
                || src.isEmpty()
                || !dst.get().isRegularFile()
                || !src.get().isRegularFile()) return null;
        if (dst.get().size() == src.get().size()
                && dst.get().lastModifiedTime().equals(src.get().lastModifiedTime())) {
            return null;
        }
        String sha = FileHashMemo.contentHash(source.toAbsolutePath().normalize());
        return sha.equals(FileHashMemo.contentHash(copy.toAbsolutePath().normalize())) ? null : sha;
    }

    /** Lay {@code source} over {@code copy} as the resource copy does: its bytes and its mtime. */
    private static void reset(Path source, Path copy) throws IOException {
        if (!Files.isRegularFile(source)) return;
        Path parent = copy.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(copy, Files.getLastModifiedTime(source));
    }

    private static void write(Path incremental, Map<String, String> held) throws IOException {
        Path ledger = incremental.resolve(MirroredOutputs.PROCESSED_LEDGER);
        if (held.isEmpty()) {
            Files.deleteIfExists(ledger);
            return;
        }
        StringBuilder sb = new StringBuilder();
        held.forEach((rel, sha) -> sb.append(rel).append('\t').append(sha).append('\n'));
        Files.createDirectories(incremental);
        AtomicWrites.replaceIfChanged(ledger, sb.toString());
    }
}
