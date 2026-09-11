// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mirrors a module's resource roots into its classes tree. The tree is shared with compiled
 * classes, so it cannot simply be made equal to the resource roots; instead a ledger records what
 * the last mirror copied, and a file that has since left every resource root is removed before
 * the copy. Without it a deleted resource stayed in the classes tree and rode into every jar.
 */
public final class ResourceMirror {

    /** The ledger's file name under the module's {@code incremental/} directory. */
    public static final String LEDGER = "copied-resources.txt";

    private ResourceMirror() {}

    /**
     * Copy every file under {@code resourceDirs} into {@code classes}, first deleting the files the
     * previous mirror copied that no resource root holds any more. Returns the relative paths now
     * mirrored; the ledger at {@code ledger} is rewritten to the same set.
     */
    public static Set<String> sync(List<Path> resourceDirs, Path classes, Path ledger) throws IOException {
        Set<String> current = new TreeSet<>();
        for (Path dir : resourceDirs) {
            PathUtil.forEachRegularFile(
                    dir,
                    (file, attrs) -> current.add(dir.relativize(file).toString().replace('\\', '/')));
        }
        for (String gone : previous(ledger)) {
            if (current.contains(gone)) continue;
            Path stale = classes.resolve(gone);
            if (Files.deleteIfExists(stale)) {
                Path parent = stale.getParent();
                if (parent != null) pruneEmptyParents(parent, classes);
            }
        }
        for (Path dir : resourceDirs) PathUtil.copyTree(dir, classes);
        if (current.isEmpty()) {
            Files.deleteIfExists(ledger);
        } else {
            Files.createDirectories(ledger.getParent());
            AtomicWrites.replace(ledger, String.join("\n", current) + "\n");
        }
        return current;
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
