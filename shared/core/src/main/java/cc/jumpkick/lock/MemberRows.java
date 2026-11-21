// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which rows of a workspace lock one member reads. A coordinate has one plain row per scope set,
 * the workspace's version, and may have partition rows carrying {@code members}; a member listed
 * on a partition reads that row in place of the plain row of the scopes it carries, every other
 * member reads the plain one. A plain row in a scope none of the member's partitions carries — the
 * processor path's Guava beside a main classpath pinned to another Guava — is still the member's
 * to read, and so is the part of a plain row on a classpath none of them lands on. A coordinate
 * only a member's own graph reaches has no plain row, and no other member reads it.
 */
public final class MemberRows {

    private MemberRows() {}

    /** The rows of {@code artifacts} the member at {@code memberPath} reads, in their original order. */
    public static List<Lockfile.Artifact> narrow(List<Lockfile.Artifact> artifacts, String memberPath) {
        // package key → every partition row the member reads for it (a main and a test dual may both be its own).
        Map<String, List<Lockfile.Artifact>> mine = new LinkedHashMap<>();
        for (Lockfile.Artifact row : artifacts) {
            if (row.members().contains(memberPath)) {
                mine.computeIfAbsent(row.packageKey(), k -> new ArrayList<>()).add(row);
            }
        }
        if (mine.isEmpty() && artifacts.stream().noneMatch(Lockfile.Artifact::isPartition)) return artifacts;
        List<Lockfile.Artifact> out = new ArrayList<>(artifacts.size());
        for (Lockfile.Artifact row : artifacts) {
            List<Lockfile.Artifact> own = mine.get(row.packageKey());
            if (own != null && own.contains(row)) {
                out.add(row);
            } else if (!row.isPartition()) {
                Lockfile.Artifact unshadowed = own == null ? row : unshadowed(row, own);
                if (unshadowed != null) out.add(unshadowed);
            }
        }
        return out;
    }

    /**
     * {@code plain} less the scopes a classpath of {@code partitions} lands on already carries, or
     * {@code null} when none is left. The main and test scopes are one family — the test classpath
     * carries the main rows, so a member's own main row displaces the workspace's test-only row of
     * the same coordinate — while the annotation processor path is a graph of its own: a member's
     * main partition leaves the processor part of a plain row the compiler needs alone, and a
     * processor partition leaves its main part.
     */
    private static Lockfile.@Nullable Artifact unshadowed(Lockfile.Artifact plain, List<Lockfile.Artifact> partitions) {
        boolean classpath = false;
        boolean processor = false;
        for (Lockfile.Artifact partition : partitions) {
            for (Scope scope : partition.scopes()) {
                if (isProcessor(scope)) {
                    processor = true;
                } else {
                    classpath = true;
                }
            }
        }
        List<Scope> kept = new ArrayList<>();
        for (Scope scope : plain.scopes()) {
            if (!(isProcessor(scope) ? processor : classpath)) kept.add(scope);
        }
        if (kept.isEmpty()) return null;
        return kept.size() == plain.scopes().size() ? plain : plain.withScopes(kept);
    }

    private static boolean isProcessor(Scope scope) {
        return scope == Scope.PROCESSOR || scope == Scope.TEST_PROCESSOR;
    }

    /**
     * {@code lock}, read from {@code lockFile}, as the module at {@code moduleDir} reads it: narrowed
     * to that member's rows when the lock is a workspace's and the module is one of its members,
     * the lock itself when the module is the lock's own directory or the lock carries no partition.
     */
    public static Lockfile view(Lockfile lock, Path lockFile, Path moduleDir) {
        if (!anyPartition(lock.artifacts())) return lock;
        Path root = lockFile.toAbsolutePath().normalize().getParent();
        Path module = moduleDir.toAbsolutePath().normalize();
        if (root == null || root.equals(module) || !module.startsWith(root)) return lock;
        return lock.forMember(root.relativize(module).toString().replace('\\', '/'));
    }

    /** True when any row of {@code artifacts} is a member partition. */
    public static boolean anyPartition(List<Lockfile.Artifact> artifacts) {
        return artifacts.stream().anyMatch(Lockfile.Artifact::isPartition);
    }
}
