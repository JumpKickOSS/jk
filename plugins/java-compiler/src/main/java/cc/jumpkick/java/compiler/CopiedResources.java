// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The main resources the engine copied into the class output before this compile, each with the
 * resource file it came from: {@code target/incremental/copied-resource-sources.tsv} for {@code
 * target/classes}, written by the engine's resource copy (the name is layer-forced: the worker
 * cannot see the engine's constant, so the two move together).
 *
 * <p>jk copies resources before compiling, as Maven does, so a processor can read one back
 * through the Filer and write that path — Helidon's codegen merges a static {@code
 * META-INF/helidon/manifest} into the one it writes. Three things follow here. A generated file
 * that is a copy is put back to its resource, never deleted, when last run's generated files are
 * cleared: the resource is still the module's and the processor's merge base. A read of a copy that
 * holds its resource's bytes is a read of the resource, not of last run's output, so it does not
 * make the module merging. And the contents of the resources a processor read through the Filer
 * join the processor digest, so an edit of one compiles every source again and the processors read
 * it anew; an edit of a resource no processor reads keeps the compile incremental.
 */
final class CopiedResources {

    static final String SOURCES = "copied-resource-sources.tsv";

    private static final CopiedResources NONE = new CopiedResources(Map.of());

    /** Copy (absolute, under the class output) to the resource it was copied from. */
    private final Map<Path, Path> sources;

    private CopiedResources(Map<Path, Path> sources) {
        this.sources = sources;
    }

    /** The copies under {@code classOutput}; none for any tree but a module's {@code target/classes}. */
    static CopiedResources of(Path classOutput) {
        Path classes = classOutput.toAbsolutePath().normalize();
        Path target = classes.getParent();
        if (target == null || !String.valueOf(classes.getFileName()).equals("classes")) return NONE;
        Path ledger = target.resolve("incremental").resolve(SOURCES);
        if (!Files.isRegularFile(ledger)) return NONE;
        Map<Path, Path> out = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String rel = GeneratedProvenance.unescape(line.substring(0, tab));
                out.put(
                        classes.resolve(rel).normalize(),
                        Path.of(GeneratedProvenance.unescape(line.substring(tab + 1))));
            }
        } catch (IOException | InvalidPathException unreadable) {
            // Without the ledger every copy reads as a file of unknown origin: the pre-copy behaviour.
            return NONE;
        }
        return new CopiedResources(out);
    }

    /** Copy to resource, keyed by {@code canon}'s spelling of each copy. */
    Map<Path, Path> byCanonical(GeneratedProvenance.Canon canon) {
        Map<Path, Path> out = new HashMap<>();
        sources.forEach((copy, source) -> out.put(canon.canonical(copy), source));
        return out;
    }

    /**
     * The class-output paths, relative and {@code /}-separated, of the resources a processor read
     * through the Filer: a copy of a module resource, or a file that was not there when it was read
     * and that no processor wrote, so adding that resource later counts as an edit.
     */
    Set<String> read(Collection<Path> reads, Collection<Path> generated, Path classOutput) {
        GeneratedProvenance.Canon canon = new GeneratedProvenance.Canon(List.of(classOutput));
        Path classRoot = canon.canonical(classOutput);
        Set<Path> copies = byCanonical(canon).keySet();
        Set<Path> written = canon.all(generated);
        Set<String> out = new TreeSet<>();
        for (Path read : reads) {
            Path file = canon.canonical(read);
            if (!file.startsWith(classRoot) || file.equals(classRoot)) continue;
            if (copies.contains(file) || (!written.contains(file) && !Files.exists(file))) {
                out.add(classRoot.relativize(file).toString().replace(File.separatorChar, '/'));
            }
        }
        return out;
    }

    /**
     * The resources at {@code read} (class-output relative) as they stand: each one's path and its
     * resource's content hash, or {@code -} when no resource is there, digested; empty when {@code
     * read} is. Bytes rather than shapes: a checkout or a reset rewrites a resource with the bytes it
     * had and a new mtime, and that must not compile every source of the module again.
     */
    String stamp(Path classOutput, Collection<String> read) throws IOException {
        if (read.isEmpty()) return "";
        Path classes = classOutput.toAbsolutePath().normalize();
        MessageDigest digest = Hashing.newSha256();
        for (String rel : new TreeSet<>(read)) {
            Path source = sources.get(classes.resolve(rel).normalize());
            String hash = source != null && Files.isRegularFile(source) ? ContentHashes.of(source) : "-";
            digest.update((rel + "\t" + hash + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return "+resources:" + Hashing.hex(digest.digest());
    }

    /** Lay {@code source} over {@code copy} as the engine's copy does, or drop the copy when its resource is gone. */
    static void reset(Path copy, Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            Files.deleteIfExists(copy);
            return;
        }
        Path parent = copy.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(copy, Files.getLastModifiedTime(source));
    }
}
