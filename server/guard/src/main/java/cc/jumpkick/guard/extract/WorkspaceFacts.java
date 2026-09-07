// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
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
    public static Optional<ClassFacts> lookup(List<Path> modules, String internalName) {
        synchronized (LOCK) {
            for (Path m : modules) {
                Path idx = indexOf(m);
                if (idx == null) continue;
                try {
                    FileTime mtime = Files.getLastModifiedTime(idx);
                    Names names = NAMES.get(idx);
                    if (names == null || !names.mtime().equals(mtime)) {
                        names = new Names(mtime, FactsFormat.read(idx).classes().keySet());
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

    private static @Nullable Path indexOf(Path module) {
        Path manifest = module.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return null;
        try {
            JkBuild build = JkBuildParser.parse(manifest);
            Path idx = FactsIndexing.indexPath(BuildLayout.of(module, build).buildDir(), "main");
            return Files.isRegularFile(idx) ? idx : null;
        } catch (IOException | RuntimeException unparseable) {
            return null;
        }
    }
}
