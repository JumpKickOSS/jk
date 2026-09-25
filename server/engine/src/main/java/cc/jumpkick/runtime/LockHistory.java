// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The lock a project had before the last rewrite that changed which scope a coordinate sits in.
 * A missing-package hint reads it to name the dependency the manifest no longer declares.
 */
final class LockHistory {

    private LockHistory() {}

    /**
     * Copy the lock on disk in {@code lockDir} aside when {@code next} changes that scope set.
     * A rewrite that only moves versions leaves the kept lock in place.
     */
    static void keep(Path lockDir, Lockfile disk, Lockfile next) {
        // The id the rewrite will record: the one already on the new lock, else the one on disk.
        String id = next.projectId();
        if (id == null || id.isBlank()) id = disk.projectId();
        if (id == null || id.isBlank()) return;
        if (scopes(disk).equals(scopes(next))) return;
        Path dest = file(id);
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(LockPaths.lockFile(lockDir), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // The hint falls through to the jars and the catalog; the rewrite still proceeds.
        }
    }

    /** The lock kept for {@code projectId}, or null when this project has not replaced one. */
    static @Nullable Lockfile read(@Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        Path dest = file(projectId);
        if (!Files.isRegularFile(dest)) return null;
        try {
            return LockfileReader.read(dest);
        } catch (Exception unreadable) {
            return null;
        }
    }

    static Path file(String projectId) {
        return JkDirs.state().resolve("lock-history").resolve(projectId + ".toml");
    }

    /** {@code g:a=scope,scope} rows. Versions are not part of it: a pin that keeps its scope is not a removal. */
    static String scopes(Lockfile lock) {
        List<String> rows = new ArrayList<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            rows.add(RemovedRoots.coordinate(a.name()) + "=" + scopeList(a.scopes()));
        }
        rows.sort(Comparator.naturalOrder());
        return String.join(";", rows);
    }

    private static String scopeList(List<Scope> scopes) {
        Set<Scope> set = EnumSet.noneOf(Scope.class);
        set.addAll(scopes);
        List<String> names = new ArrayList<>();
        for (Scope s : set) names.add(s.name());
        names.sort(Comparator.naturalOrder());
        return String.join(",", names);
    }
}
