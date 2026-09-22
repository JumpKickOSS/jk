// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Workspace members a module reaches only through a published POM edge. The resolver serves such
 * an edge from the member and locks no row for the coordinate, so the member is on the consumer's
 * classpath without any declared {@code workspace = true} dependency naming it.
 *
 * <p>Classpath assembly and build ordering both read this, so a member that lands on a module's
 * compile classpath is also a module the build schedules first.
 */
public final class PomSubstitution {

    private PomSubstitution() {}

    /**
     * Member coordinates {@code module} reaches through the lock's published rows, walking from
     * its own non-workspace dependencies. Empty when the workspace has no lock, the lock will not
     * read, or nothing published points at a member.
     *
     * @param root the workspace root holding {@code jk-lock.toml}
     * @param memberCoords every {@code group:artifact} the workspace builds
     */
    public static Set<String> membersBehindPublishedEdges(
            Path root, JkBuild module, Collection<Scope> scopes, Set<String> memberCoords) {
        if (memberCoords.isEmpty()) return Set.of();
        Lockfile lock = readLock(root);
        if (lock == null) return Set.of();

        Map<String, Lockfile.Artifact> byName = new HashMap<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            byName.put(row.name(), row);
            String ga = groupArtifact(row.name());
            if (!ga.equals(row.name())) byName.putIfAbsent(ga, row);
        }

        Queue<String> artifacts = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (Scope scope : scopes) {
            for (Dependency dep : module.dependencies().of(scope)) {
                if (dep.isWorkspace() || dep.isGit() || dep.isPath()) continue;
                if (seen.add(dep.module())) artifacts.add(dep.module());
            }
        }
        Set<String> members = new LinkedHashSet<>();
        while (!artifacts.isEmpty()) {
            Lockfile.Artifact row = byName.get(artifacts.poll());
            if (row == null) continue;
            for (String depRef : row.deps()) {
                String child = stripVersion(depRef);
                String ga = groupArtifact(child);
                if (memberCoords.contains(ga)) {
                    members.add(ga);
                    continue;
                }
                if (seen.add(child)) artifacts.add(child);
                if (!ga.equals(child) && seen.add(ga)) artifacts.add(ga);
            }
        }
        return members;
    }

    /** The lock at {@code root}, or null when there is none or it will not parse. */
    private static @Nullable Lockfile readLock(Path root) {
        Path lockFile = LockPaths.lockFile(root);
        if (!Files.isRegularFile(lockFile)) return null;
        try {
            return LockfileReader.read(lockFile);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** A dep reference without its {@code @version} tail. */
    private static String stripVersion(String depRef) {
        int at = depRef.indexOf('@');
        return at >= 0 ? depRef.substring(0, at) : depRef;
    }

    /** The {@code group:artifact} prefix of a package key, or the key when it has no third field. */
    private static String groupArtifact(String key) {
        int colon = key.indexOf(':');
        int second = colon < 0 ? -1 : key.indexOf(':', colon + 1);
        return second > 0 ? key.substring(0, second) : key;
    }
}
