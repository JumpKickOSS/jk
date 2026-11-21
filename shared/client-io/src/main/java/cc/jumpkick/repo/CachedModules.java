// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The modules cached under every known store of a cache root, merged by module key: the
 * repo-agnostic view offline search and near-miss suggestions read, since neither is scoped to one
 * declared repository.
 */
public final class CachedModules {

    private CachedModules() {}

    /**
     * Every {@code group:artifact} cached under any known store in {@code cacheRoot}, merged by
     * module key — the repo-agnostic view {@code jk repo search} and {@code jk library search
     * --offline} want, since neither is scoped to one particular declared repository.
     */
    public static List<RepoArtifactStore.Module> allModules(Path cacheRoot) {
        Map<String, String[]> ga = new LinkedHashMap<>();
        Map<String, Set<String>> versionsByModule = new LinkedHashMap<>();
        for (String storeId : RepoArtifactStore.storeIds(cacheRoot)) {
            for (RepoArtifactStore.Module m :
                    RepoArtifactStore.forStoreId(cacheRoot, storeId).modules()) {
                ga.putIfAbsent(m.moduleKey(), new String[] {m.group(), m.artifact()});
                versionsByModule
                        .computeIfAbsent(m.moduleKey(), k -> new LinkedHashSet<>())
                        .addAll(m.versions());
            }
        }
        List<RepoArtifactStore.Module> out = new ArrayList<>();
        for (var e : versionsByModule.entrySet()) {
            String[] parts = Objects.requireNonNull(ga.get(e.getKey()));
            out.add(new RepoArtifactStore.Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /** Narrows {@link #moduleKeys} to the modules a caller can use, so the walk skips the rest of the tree. */
    public interface ModuleFilter {

        /** False when no wanted module's group starts with {@code groupPrefix}'s segments: that directory is not listed. */
        boolean mayHold(String groupPrefix);

        /** True when {@code group:artifact} is wanted; only then is its directory read for a version. */
        boolean wants(String group, String artifact);

        /** Called once for each directory the walk lists, before it is read. */
        default void listing(Path dir) {}
    }

    /**
     * The keys of {@link #allModules} that {@code filter} wants, in that list's order. Only the
     * group directories {@link ModuleFilter#mayHold} admits are listed, and only a wanted
     * artifact's version directories, so the cost follows the filter and not the store's size.
     */
    public static List<String> moduleKeys(Path cacheRoot, ModuleFilter filter) {
        Set<String> out = new LinkedHashSet<>();
        for (String storeId : RepoArtifactStore.storeIds(cacheRoot)) {
            Path tree;
            try {
                tree = RepoArtifactStore.dirFor(cacheRoot, storeId).toRealPath();
            } catch (IOException e) {
                continue;
            }
            Set<String> keys = new TreeSet<>();
            collectKeys(tree, "", filter, keys);
            out.addAll(keys);
        }
        return List.copyOf(out);
    }

    private static void collectKeys(Path dir, String group, ModuleFilter filter, Set<String> keys) {
        for (Path child : subdirectories(dir, filter)) {
            String name = child.getFileName().toString();
            if (!group.isEmpty() && filter.wants(group, name) && holdsVersion(child, filter))
                keys.add(group + ":" + name);
            String deeper = group.isEmpty() ? name : group + "." + name;
            if (filter.mayHold(deeper)) collectKeys(child, deeper, filter, keys);
        }
    }

    /** The real directories under {@code dir}, not the ledger: a link to a directory is not entered, as {@link RepoArtifactStore#modules} does not. */
    private static List<Path> subdirectories(Path dir, ModuleFilter filter) {
        filter.listing(dir);
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (attrs.isDirectory() && !RepoArtifactStore.isAccessDir(child)) out.add(child);
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /** True when a version directory under {@code artifactDir} holds a tracked file: a module as {@link RepoArtifactStore#modules} counts one. */
    private static boolean holdsVersion(Path artifactDir, ModuleFilter filter) {
        for (Path version : subdirectories(artifactDir, filter)) {
            boolean[] tracked = {false};
            filter.listing(version);
            try {
                PathUtil.forEachChild(version, (file, attrs) -> {
                    boolean regular = attrs.isRegularFile() || (attrs.isSymbolicLink() && Files.isRegularFile(file));
                    if (regular
                            && !RepoArtifactStore.isBookkeeping(
                                    file.getFileName().toString())) tracked[0] = true;
                    return !tracked[0];
                });
            } catch (IOException e) {
                continue;
            }
            if (tracked[0]) return true;
        }
        return false;
    }

    /**
     * Versions of {@code group:artifact} cached under any known store in {@code cacheRoot}, merged
     * and deduplicated — the repo-agnostic counterpart to {@link RepoArtifactStore#versions(String, String)}.
     */
    public static List<String> allVersions(Path cacheRoot, String group, String artifact) {
        Set<String> out = new LinkedHashSet<>();
        for (String storeId : RepoArtifactStore.storeIds(cacheRoot)) {
            out.addAll(RepoArtifactStore.forStoreId(cacheRoot, storeId).versions(group, artifact));
        }
        return List.copyOf(out);
    }
}
