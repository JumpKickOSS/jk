// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
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
     * contributes nothing (its compile failed or never ran), nor does a class of a source that is
     * gone ({@link #removedClasses}); a class two modules both compile is kept once, under the module
     * read later — the split-package kind reads the per-module indexes.
     */
    public static FactsIndex merged(Path root, List<Path> modules) throws IOException {
        Map<String, ClassFacts> classes = new LinkedHashMap<>();
        for (Path m : modules) {
            Path idx = indexOf(root, m);
            if (idx == null) continue;
            FactsIndex index = FactsFormat.read(idx);
            Set<String> removed = removedClasses(root, m, index);
            for (var c : index.classes().entrySet()) {
                if (!removed.contains(c.getKey())) classes.put(c.getKey(), c.getValue());
            }
        }
        return new FactsIndex(classes, Map.of(), "");
    }

    /**
     * The classes {@code module}'s main index holds from a source its last compile read and that is
     * gone since, with none of its source roots holding it again, by internal name. A module no step
     * of this run compiled keeps such classes until it compiles again; the workspace lane reads them
     * as not there. Empty when every source the stamps name is on disk.
     */
    public static Set<String> removedClasses(Path root, Path module) {
        return removedClasses(root, module, null);
    }

    /** {@link #removedClasses(Path, Path)} with {@code index} already read; null reads it only when a source is gone. */
    private static Set<String> removedClasses(Path root, Path module, @Nullable FactsIndex index) {
        List<String> gone = goneSources(module);
        if (gone.isEmpty()) return Set.of();
        FactsIndex read = index != null ? index : module(root, module);
        Set<String> out = new TreeSet<>();
        for (var c : read.classes().entrySet()) {
            String tail = SourceRoots.tail(c.getValue());
            if (tail == null || !endsWithAny(gone, "/" + tail)) continue;
            if (SourceRoots.rootHolding(module, tail) == null) out.add(c.getKey());
        }
        return out;
    }

    /** The sources the stamps of {@code module}'s classes tree name that are not on disk, {@code /}-separated. */
    private static List<String> goneSources(Path module) {
        List<String> out = new ArrayList<>();
        Path classes = BuildLayout.moduleTargetDir(module).resolve("classes");
        for (Path source : BuildStamps.stampedSources(classes)) {
            if (!Files.exists(source)) out.add(source.toString().replace('\\', '/'));
        }
        return out;
    }

    private static boolean endsWithAny(List<String> paths, String suffix) {
        for (String p : paths) if (p.endsWith(suffix)) return true;
        return false;
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
     * Every workspace package's module directories, from the index names: a module that compiles a
     * class of the package, even one another module compiles too, is among them; one that holds the
     * package only through classes of sources that are gone ({@link #removedClasses}) is not.
     */
    public static Map<String, Set<Path>> packageModuleDirs(Path root, List<Path> modules) {
        Map<String, Set<Path>> out = new TreeMap<>();
        for (Path m : modules) {
            Path idx = indexOf(root, m);
            if (idx == null) continue;
            try {
                FileStamp stamp = FileStamp.of(idx);
                if (stamp == null) continue;
                Set<String> removed = removedClasses(root, m, null);
                for (String c : names(idx, stamp)) {
                    if (removed.contains(c)) continue;
                    out.computeIfAbsent(Descriptors.packageOf(c), k -> new LinkedHashSet<>())
                            .add(m);
                }
            } catch (RuntimeException unreadable) {
                // A module whose index cannot be read declares no package here.
                Log.debug("packageModules: a module whose index cannot be read declares no package here", unreadable);
            }
        }
        return out;
    }

    /**
     * Per module, the packages of its main sources that the workspace lane's indexes do not show:
     * every one for a module no index gives a package ({@link #packageModuleDirs}), else those of
     * compilable sources its last compile's stamps do not name, added or moved in since. A module
     * with stamps that name nothing new, or with no source, is absent. The split-package kind reads
     * these before it calls an allow stale.
     */
    public static Map<Path, Set<String>> unseenPackages(Path root, List<Path> modules) {
        Set<Path> indexed = new HashSet<>();
        for (Set<Path> dirs : packageModuleDirs(root, modules).values()) indexed.addAll(dirs);
        return unseenPackages(modules, indexed);
    }

    /** {@link #unseenPackages(Path, List)} with the modules an index gives a package already known. */
    public static Map<Path, Set<String>> unseenPackages(List<Path> modules, Set<Path> indexed) {
        Map<Path, Set<String>> out = new LinkedHashMap<>();
        for (Path module : modules) {
            Set<String> packages = indexed.contains(module) ? unreadPackages(module) : sourcePackages(module);
            if (!packages.isEmpty()) out.put(module, packages);
        }
        return out;
    }

    /** Every package with at least one file in a main source root of {@code module}. */
    private static Set<String> sourcePackages(Path module) {
        Set<String> out = new TreeSet<>();
        packagesOf(module, file -> true, out);
        return out;
    }

    /**
     * Every package of a compilable source in a main root of {@code module} that the stamps of its
     * last compile do not name. A module with no stamp names nothing it could be behind.
     */
    private static Set<String> unreadPackages(Path module) {
        Set<String> out = new TreeSet<>();
        List<Path> stamped =
                BuildStamps.stampedSources(BuildLayout.moduleTargetDir(module).resolve("classes"));
        if (stamped.isEmpty()) return out;
        Set<Path> read = new HashSet<>();
        for (Path source : stamped) read.add(source.toAbsolutePath().normalize());
        packagesOf(
                module,
                file -> compilable(file) && !read.contains(file.toAbsolutePath().normalize()),
                out);
        return out;
    }

    private static boolean compilable(Path file) {
        String name = String.valueOf(file.getFileName());
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy");
    }

    /** Adds to {@code out} the package of each file {@code take} accepts in a main source root of {@code module}. */
    private static void packagesOf(Path module, Predicate<Path> take, Set<String> out) {
        for (Path root : SourceRoots.mainRoots(module, ModuleLayout.isCompact(module))) {
            if (!Files.isDirectory(root)) continue;
            try {
                PathUtil.forEachReadableFile(root, (file, attrs) -> {
                    Path dir = root.relativize(file).getParent();
                    if (dir != null && take.test(file))
                        out.add(dir.toString().replace(dir.getFileSystem().getSeparator(), "."));
                });
            } catch (IOException unreadable) {
                // A tree the lane cannot list declares nothing it could defend an allow with.
            }
        }
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
