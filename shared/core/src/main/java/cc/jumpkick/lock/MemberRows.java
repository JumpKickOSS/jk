// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which rows of a workspace lock one member reads. A coordinate has one plain row, the
 * workspace's version, and may have partition rows carrying {@code members}; a member listed on a
 * partition reads that row in place of the plain one, every other member reads the plain one. A
 * coordinate only a member's own graph reaches has no plain row, and no other member reads it.
 */
public final class MemberRows {

    private MemberRows() {}

    /** The rows of {@code artifacts} the member at {@code memberPath} reads, in their original order. */
    public static List<Lockfile.Artifact> narrow(List<Lockfile.Artifact> artifacts, String memberPath) {
        Map<String, Lockfile.Artifact> mine = new LinkedHashMap<>();
        for (Lockfile.Artifact row : artifacts) {
            if (row.members().contains(memberPath)) mine.put(row.packageKey(), row);
        }
        if (mine.isEmpty() && artifacts.stream().noneMatch(Lockfile.Artifact::isPartition)) return artifacts;
        List<Lockfile.Artifact> out = new ArrayList<>(artifacts.size());
        for (Lockfile.Artifact row : artifacts) {
            Lockfile.Artifact own = mine.get(row.packageKey());
            if (own != null) {
                if (own == row) out.add(row);
                continue;
            }
            if (!row.isPartition()) out.add(row);
        }
        return out;
    }

    /** True when any row of {@code artifacts} is a member partition. */
    public static boolean anyPartition(List<Lockfile.Artifact> artifacts) {
        return artifacts.stream().anyMatch(Lockfile.Artifact::isPartition);
    }
}
