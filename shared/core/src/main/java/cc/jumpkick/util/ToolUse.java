// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records that a tool install under {@code <store>/tools} was used, which is what the tools collector
 * counts down from. Every resolver that hands an install to a build calls {@link #record}: the
 * install's directory is stamped (see {@link PoolUse}) and the use is added to the store's access
 * ledger, where how often it is used lengthens its window. The first use marks the family's tree as
 * one that wants stamps, so a store that predates the collector starts keeping them without a migration.
 */
public final class ToolUse {

    private ToolUse() {}

    /** The ledger key of {@code family/version}. */
    public static String key(String family, String version) {
        return "tool:" + family + "/" + version;
    }

    /** True when the install a ledger {@code key} names is still on disk. */
    public static boolean exists(Path toolsRoot, String key) {
        if (!key.startsWith("tool:")) return false;
        String[] parts = key.substring("tool:".length()).split("/", 2);
        return parts.length == 2
                && Files.isDirectory(toolsRoot.resolve(parts[0]).resolve(parts[1]));
    }

    /** The ledger that records the uses of installs under {@code toolsRoot}. */
    public static AccessLedger ledger(Path toolsRoot) {
        return AccessLedger.at(toolsRoot.resolve(".access"));
    }

    /** Record a use of {@code install}, the directory {@code toolsRoot/family/version}. Never throws. */
    public static void record(Path toolsRoot, String family, Path install) {
        Path familyDir = toolsRoot.resolve(family);
        try {
            PoolUse.mark(familyDir);
        } catch (IOException | RuntimeException unwritable) {
            return; // a read-only store keeps no stamps; its installs fall back to their own mtime
        }
        PoolUse.touch(familyDir, install);
        ledger(toolsRoot).touch(key(family, install.getFileName().toString()));
    }
}
