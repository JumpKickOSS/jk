// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.task.MirroredOutputs;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        Set<String> current = new TreeSet<>();
        for (Path dir : resourceDirs) {
            PathUtil.forEachRegularFile(
                    dir,
                    (file, attrs) -> current.add(dir.relativize(file).toString().replace('\\', '/')));
        }
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
                Path parent = stale.getParent();
                if (parent != null) pruneEmptyParents(parent, classes);
            }
        }
        for (Path dir : resourceDirs) PathUtil.copyTree(dir, classes);
        Path parent = ledger.getParent();
        if (parent != null) Files.createDirectories(parent);
        AtomicWrites.replace(ledger, current.isEmpty() ? "" : String.join("\n", current) + "\n");
        return current;
    }

    /**
     * Size and mtime of each non-class file under {@code classes}, keyed by relative path. A later
     * {@link #changedNonClass} names what a compile rewrote, which adoption must not remove.
     */
    public static Map<String, String> nonClassIdentity(Path classes) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(classes)) return out;
        PathUtil.forEachRegularFile(classes, (file, attrs) -> {
            String rel = classes.relativize(file).toString().replace('\\', '/');
            if (rel.endsWith(".class")) return;
            out.put(rel, attrs.size() + ":" + attrs.lastModifiedTime().toMillis());
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
            String id = attrs.size() + ":" + attrs.lastModifiedTime().toMillis();
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
            if (rel.endsWith(".class") || BuildStamps.isStampFile(rel)) return;
            if (rel.endsWith(".kotlin_module") || rel.endsWith(".tasty")) return;
            if (merged.contains(rel) || scratch(rel)) return;
            out.add(rel);
        });
        return out;
    }

    private static boolean scratch(String rel) {
        for (int start = 0; start <= rel.length(); ) {
            int slash = rel.indexOf('/', start);
            String seg = slash < 0 ? rel.substring(start) : rel.substring(start, slash);
            if (seg.startsWith(".jk-")) return true;
            if (slash < 0) break;
            start = slash + 1;
        }
        return false;
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
