// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.host.TreeContent;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.MirroredOutputs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mirrors a module's resource roots into its classes tree. The tree is shared with compiled
 * classes, so it cannot simply be made equal to the resource roots; instead a ledger records what
 * the last mirror copied, and a file that has since left every resource root is removed before
 * the copy. Without it a deleted resource stayed in the classes tree and rode into every jar.
 */
public final class ResourceMirror {

    /** The main-classes ledger's file name under the module's {@code incremental/} directory. */
    public static final String LEDGER = MirroredOutputs.MAIN_LEDGER;

    /** The test-classes ledger's file name, beside {@link #LEDGER}. */
    public static final String TEST_LEDGER = MirroredOutputs.TEST_LEDGER;

    private ResourceMirror() {}

    /**
     * Copy every file under {@code resourceDirs} into {@code classes}, first deleting the files the
     * previous mirror copied that no resource root holds any more. Returns the relative paths now
     * mirrored; the ledger at {@code ledger} is rewritten to the same set.
     */
    public static Set<String> sync(List<Path> resourceDirs, Path classes, Path ledger) throws IOException {
        return sync(resourceDirs, classes, ledger, false);
    }

    /**
     * As {@link #sync(List, Path, Path, boolean)} with nothing protected from adoption.
     */
    public static Set<String> sync(List<Path> resourceDirs, Path classes, Path ledger, boolean adoptUnowned)
            throws IOException {
        return sync(resourceDirs, classes, ledger, adoptUnowned, Set.of());
    }

    /**
     * As {@link #sync(List, Path, Path)}. When {@code adoptUnowned} is set and this tree has no
     * ledger yet, a non-class file already in {@code classes} that no resource root holds is
     * removed too — a copy that predates the ledger, which otherwise stays forever. {@code protect}
     * are relative paths that adoption must leave: outputs the compile that just ran wrote.
     *
     * <p>The ledger file is written even when nothing is mirrored, so a later sync can tell "owns
     * nothing" from "has never run" and does not adopt again.
     */
    public static Set<String> sync(
            List<Path> resourceDirs, Path classes, Path ledger, boolean adoptUnowned, Set<String> protect)
            throws IOException {
        return mirror(resourceDirs, Map.of(), classes, ledger, adoptUnowned, protect, Map.of())
                .files();
    }

    /** As {@link #sync(List, Path, Path)}, answering how many files under {@code classes} it wrote or removed. */
    public static int syncChanges(List<Path> resourceDirs, Path classes, Path ledger) throws IOException {
        return mirror(resourceDirs, Map.of(), classes, ledger, false, Set.of(), Map.of())
                .changes();
    }

    /**
     * What {@link #syncMain} did: how many files under the classes tree it wrote or removed, and
     * the resource file each mirrored path was copied from (the last root holding it wins).
     */
    public record Main(int changes, Map<String, Path> sources) {}

    /**
     * The main-resource mirror, which runs before the compile: as {@link #syncChanges}, plus {@code
     * files} (tree-relative path to a single file, laid after the roots — a module-root plugin
     * manifest), leaving every {@code held} path alone while the resource it would be copied from
     * still has the content sha {@code held} names. A processor rewrote that copy in the last
     * compile, so the file there is the compile's output; a changed resource is copied again, and
     * the compile, whose key names the resources, runs again over it.
     */
    public static Main syncMain(
            List<Path> resourceDirs, Map<String, Path> files, Path classes, Path ledger, Map<String, String> held)
            throws IOException {
        Mirrored m = mirror(resourceDirs, files, classes, ledger, false, Set.of(), held);
        return new Main(m.changes(), m.sources());
    }

    private record Mirrored(Set<String> files, int changes, Map<String, Path> sources) {}

    private static Mirrored mirror(
            List<Path> resourceDirs,
            Map<String, Path> files,
            Path classes,
            Path ledger,
            boolean adoptUnowned,
            Set<String> protect,
            Map<String, String> held)
            throws IOException {
        int changes = 0;
        Map<String, Path> sources = new TreeMap<>();
        for (Path dir : resourceDirs) {
            PathUtil.forEachRegularFile(
                    dir,
                    (file, attrs) -> sources.put(dir.relativize(file).toString().replace('\\', '/'), file));
        }
        sources.putAll(files);
        Set<String> current = new TreeSet<>(sources.keySet());
        Set<String> owned;
        if (Files.isRegularFile(ledger)) owned = previous(ledger);
        else if (adoptUnowned) {
            owned = new TreeSet<>(adopted(classes, ledger));
            owned.removeAll(protect);
        } else owned = Set.of();
        for (String gone : owned) {
            if (current.contains(gone)) continue;
            Path stale = classes.resolve(gone);
            if (Files.deleteIfExists(stale)) {
                changes++;
                Path parent = stale.getParent();
                if (parent != null) pruneEmptyParents(parent, classes);
            }
        }
        Set<Path> kept = kept(held, sources, classes);
        for (Path dir : resourceDirs) changes += PathUtil.copyTree(dir, classes, d -> false, kept::contains);
        for (var file : files.entrySet()) {
            Path target = classes.resolve(file.getKey());
            if (!kept.contains(target) && copyIfChanged(file.getValue(), target)) changes++;
        }
        Path parent = ledger.getParent();
        if (parent != null) Files.createDirectories(parent);
        AtomicWrites.replaceIfChanged(ledger, current.isEmpty() ? "" : String.join("\n", current) + "\n");
        return new Mirrored(current, changes, sources);
    }

    /** The {@code held} copies still on disk whose resource has the content sha recorded for it. */
    private static Set<Path> kept(Map<String, String> held, Map<String, Path> sources, Path classes)
            throws IOException {
        Set<Path> out = new HashSet<>();
        for (var e : held.entrySet()) {
            Path source = sources.get(e.getKey());
            Path target = classes.resolve(e.getKey());
            if (source == null || !Files.isRegularFile(target)) continue;
            if (FileHashMemo.contentHash(source).equals(e.getValue())) out.add(target);
        }
        return out;
    }

    /** Copy {@code source} over {@code target} unless it already has the source's size and mtime. */
    private static boolean copyIfChanged(Path source, Path target) throws IOException {
        BasicFileAttributes src = Files.readAttributes(source, BasicFileAttributes.class);
        Optional<BasicFileAttributes> dst = PathUtil.stat(target);
        if (dst.isPresent()
                && dst.get().isRegularFile()
                && dst.get().size() == src.size()
                && dst.get().lastModifiedTime().equals(src.lastModifiedTime())) {
            return false;
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(target, src.lastModifiedTime());
        return true;
    }

    /**
     * Lay every main resource the last {@link #syncMain} copied back into {@code classes}, after a
     * compile started the tree over: the copy ran before the compile, so nothing else would put
     * them back this build. A processor's rewrite of a copy went with the tree, so the ledger of
     * those is dropped too.
     */
    public static void relay(Path classes, Path incremental) throws IOException {
        for (var e : MirroredOutputs.sources(incremental).entrySet()) {
            if (Files.isRegularFile(e.getValue())) copyIfChanged(e.getValue(), classes.resolve(e.getKey()));
        }
        ProcessedResources.clear(incremental);
    }

    /**
     * The {@link StatIdentity} of each non-class file under {@code classes}, keyed by relative path. A later
     * {@link #changedNonClass} names what a compile rewrote, which adoption must not remove.
     */
    public static Map<String, String> nonClassIdentity(Path classes) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(classes)) return out;
        PathUtil.forEachRegularFile(classes, (file, attrs) -> {
            String rel = classes.relativize(file).toString().replace('\\', '/');
            if (rel.endsWith(".class")) return;
            out.put(rel, StatIdentity.of(file, attrs).encode());
        });
        return out;
    }

    /** Relative paths under {@code classes} whose non-class identity differs from {@code before}, including new files. */
    public static Set<String> changedNonClass(Path classes, Map<String, String> before) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(classes)) return out;
        PathUtil.forEachRegularFile(classes, (file, attrs) -> {
            String rel = classes.relativize(file).toString().replace('\\', '/');
            if (rel.endsWith(".class")) return;
            String id = StatIdentity.of(file, attrs).encode();
            if (!id.equals(before.get(rel))) out.add(rel);
        });
        return out;
    }

    /**
     * Non-class files already in {@code classes} that no compiler merge ledger owns. The first
     * mirror of a tree that was filled by a plain copy uses this as the set it may delete.
     */
    private static Set<String> adopted(Path classes, Path ledger) throws IOException {
        if (!Files.isDirectory(classes)) return Set.of();
        Set<String> merged = new TreeSet<>();
        Path inc = ledger.getParent();
        if (inc != null) {
            PathUtil.forEachChild(inc, (file, attrs) -> {
                if (!attrs.isRegularFile()) return true;
                String name = file.getFileName().toString();
                if (name.startsWith("merged-") && name.endsWith(".txt")) merged.addAll(previous(file));
                return true;
            });
        }
        Set<String> out = new TreeSet<>();
        PathUtil.forEachRegularFile(classes, (file, attrs) -> {
            String rel = classes.relativize(file).toString().replace('\\', '/');
            if (rel.endsWith(".class") || !TreeContent.isContent(rel)) return;
            if (rel.endsWith(".kotlin_module") || rel.endsWith(".tasty")) return;
            if (merged.contains(rel)) return;
            out.add(rel);
        });
        return out;
    }

    /**
     * Whether the next mirror would delete a copy, read-only: a file the last mirror copied (the
     * ledger) that no root in {@code sourceRoots} and none of {@code files} holds any more, and that
     * is still in {@code classes}. The forecast's half of the mirror's first pass.
     */
    public static boolean deletesCopies(List<Path> sourceRoots, Set<String> files, Path classes, Path ledger)
            throws IOException {
        return !copiesToDelete(sourceRoots, files, classes, ledger).isEmpty();
    }

    /** The tree-relative copies {@link #deletesCopies} finds: what the next mirror removes. */
    public static Set<String> copiesToDelete(List<Path> sourceRoots, Set<String> files, Path classes, Path ledger)
            throws IOException {
        Set<String> owned = previous(ledger);
        if (owned.isEmpty()) return Set.of();
        Set<String> current = new HashSet<>(files);
        for (Path dir : sourceRoots) {
            PathUtil.forEachRegularFile(
                    dir,
                    (file, attrs) -> current.add(dir.relativize(file).toString().replace('\\', '/')));
        }
        Set<String> out = new TreeSet<>();
        for (String rel : owned) {
            if (!current.contains(rel) && Files.isRegularFile(classes.resolve(rel))) out.add(rel);
        }
        return out;
    }

    private static Set<String> previous(Path ledger) throws IOException {
        if (!Files.isRegularFile(ledger)) return Set.of();
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) out.add(line.strip());
        }
        return out;
    }

    /** A directory the mirror emptied is the mirror's to remove; stop at the classes root. */
    private static void pruneEmptyParents(Path dir, Path classes) throws IOException {
        Path cur = dir;
        while (!cur.equals(classes) && cur.startsWith(classes)) {
            boolean[] occupied = {false};
            PathUtil.forEachChild(cur, (child, attrs) -> {
                occupied[0] = true;
                return false;
            });
            if (occupied[0]) return;
            Files.delete(cur);
            Path up = cur.getParent();
            if (up == null) return;
            cur = up;
        }
    }
}
