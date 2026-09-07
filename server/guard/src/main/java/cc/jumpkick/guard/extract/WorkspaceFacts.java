// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A class's facts from any module's main index in the workspace, for a rule whose owner lives in a
 * module this lane's module does not depend on ({@code vocabulary}: the ban list is the owner's
 * constants, the scan is this module's text).
 *
 * <p>Thirty module lanes asking at once must not each deserialise thirty indexes: loading is
 * serialised, an index is read once per modification time and only its class <em>names</em> are
 * kept, and the owner's facts are then read from the one index that holds them and memoised by
 * name. The whole memo is a few thousand strings and a handful of classes, never an index.
 */
public final class WorkspaceFacts {

    private record Names(FileTime mtime, Set<String> classNames) {}

    private record Owner(FileTime mtime, @Nullable ClassFacts facts) {}

    private static final Object LOCK = new Object();
    private static final Map<Path, Names> NAMES = new HashMap<>();
    private static final Map<String, Owner> OWNERS = new HashMap<>();

    private WorkspaceFacts() {}

    /** {@code internalName}'s facts from the first module index that holds it, or empty. */
    public static Optional<ClassFacts> lookup(Path root, List<Path> modules, String internalName) {
        synchronized (LOCK) {
            for (Path m : modules) {
                Path idx = indexOf(root, m);
                if (idx == null) continue;
                try {
                    FileTime mtime = Files.getLastModifiedTime(idx);
                    Names names = NAMES.get(idx);
                    if (names == null || !names.mtime().equals(mtime)) {
                        // A copy: a keySet view would keep the whole index reachable behind it.
                        names = new Names(
                                mtime,
                                Set.copyOf(FactsFormat.read(idx).classes().keySet()));
                        NAMES.put(idx, names);
                    }
                    if (!names.classNames().contains(internalName)) continue;
                    String key = idx + "#" + internalName;
                    Owner owner = OWNERS.get(key);
                    if (owner == null || !owner.mtime().equals(mtime)) {
                        FactsIndex index = FactsFormat.read(idx);
                        owner = new Owner(mtime, index.classes().get(internalName));
                        OWNERS.put(key, owner);
                    }
                    if (owner.facts() != null) return Optional.of(owner.facts());
                } catch (IOException | RuntimeException unreadable) {
                    // A module whose index cannot be read holds no answer for this lookup.
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Every module's main index read together: the workspace lane's view. A module without an index
     * contributes nothing (its compile failed or never ran); a class two modules both compile is
     * kept once, under the module read later — the split-package kind reads the per-module indexes.
     */
    public static FactsIndex merged(Path root, List<Path> modules) throws IOException {
        Map<String, ClassFacts> classes = new LinkedHashMap<>();
        for (Path m : modules) {
            Path idx = indexOf(root, m);
            if (idx == null) continue;
            classes.putAll(FactsFormat.read(idx).classes());
        }
        return new FactsIndex(classes, Map.of(), "");
    }

    /** The module's main index by the layout rule alone — no manifest is parsed for a lookup. */
    private static @Nullable Path indexOf(Path root, Path module) {
        Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, module), "main");
        return Files.isRegularFile(idx) ? idx : null;
    }
}
