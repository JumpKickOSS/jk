// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Thirty module lanes asking at once must not each deserialise thirty indexes: an index is
 * read once per settled stat and only its class <em>names</em> are
 * kept, and the owner's facts are then read from the one index that holds them and memoised by
 * name. The whole memo is a few thousand strings and a handful of classes, never an index.
 */
public final class WorkspaceFacts {

    /** An index's class names, while its settled stat holds. */
    private static final StampedMemo<Path, FileStamp, Set<String>> NAMES = StampedMemo.bounded(512);

    /** One class's facts in one index, while the index's settled stat holds. */
    private static final StampedMemo<OwnerKey, FileStamp, Optional<ClassFacts>> OWNERS = StampedMemo.bounded(4_096);

    private record OwnerKey(Path index, String internalName) {}

    private WorkspaceFacts() {}

    /** {@code internalName}'s facts from the first module index that holds it, or empty. */
    public static Optional<ClassFacts> lookup(Path root, List<Path> modules, String internalName) {
        for (Path m : modules) {
            Path idx = indexOf(root, m);
            if (idx == null) continue;
            try {
                FileStamp stamp = FileStamp.of(idx);
                if (stamp == null || !names(idx, stamp).contains(internalName)) continue;
                Optional<ClassFacts> owner = OWNERS.get(
                        new OwnerKey(idx, internalName),
                        stamp,
                        () -> Optional.ofNullable(read(idx).classes().get(internalName)));
                if (owner.isPresent()) return owner;
            } catch (RuntimeException unreadable) {
                // A module whose index cannot be read holds no answer for this lookup.
                Log.debug("lookup: A module whose index cannot be read holds no answer for this lookup", unreadable);
            }
        }
        return Optional.empty();
    }

    /** {@code idx}'s class names; a copy, since a keySet view would keep the whole index reachable. */
    private static Set<String> names(Path idx, FileStamp stamp) {
        return NAMES.get(idx, stamp, () -> Set.copyOf(read(idx).classes().keySet()));
    }

    private static FactsIndex read(Path idx) {
        try {
            return FactsFormat.read(idx);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    /**
     * One module's main index, brought up to date from its classes as {@link #merged} reads it; empty
     * when the module has none (its compile failed or never ran) or it cannot be read.
     */
    public static FactsIndex module(Path root, Path module) {
        Path idx = indexOf(root, module);
        if (idx == null) return FactsIndex.EMPTY;
        try {
            return FactsFormat.read(idx);
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("module: an index that cannot be read shows no class", unreadable);
            return FactsIndex.EMPTY;
        }
    }

    /** Every workspace class's module directory, from the index names alone; the first index that holds a class owns it. */
    public static Map<String, Path> classModuleDirs(Path root, List<Path> modules) {
        Map<String, Path> out = new HashMap<>();
        for (Path m : modules) {
            Path idx = indexOf(root, m);
            if (idx == null) continue;
            try {
                FileStamp stamp = FileStamp.of(idx);
                if (stamp == null) continue;
                for (String c : names(idx, stamp)) out.putIfAbsent(c, m);
            } catch (RuntimeException unreadable) {
                // A module whose index cannot be read owns no class here.
                Log.debug("classModules: A module whose index cannot be read owns no class here", unreadable);
            }
        }
        return out;
    }

    /**
     * The module's main index by the layout rule alone — no manifest is parsed for a lookup — brought
     * up to date from its classes first. The workspace lane can run as soon as a module has
     * published its jar, which is before that module's own guard lane has indexed its classes; a
     * lane that read only indexes already on disk would find nothing and call the workspace blind.
     * Without a classes tree the index on disk, if any, is the module's word.
     */
    private static @Nullable Path indexOf(Path root, Path module) {
        Path buildDir = BuildLayout.moduleTargetDir(module);
        Path idx = FactsIndexing.indexPath(buildDir, "main");
        Path classes = buildDir.resolve("classes");
        if (Files.isDirectory(classes)) {
            try {
                FactsIndexing.ensure(classes, idx);
            } catch (IOException unreadable) {
                // The index already there, or nothing: the lane reports what it could read.
            }
        }
        return Files.isRegularFile(idx) ? idx : null;
    }
}
