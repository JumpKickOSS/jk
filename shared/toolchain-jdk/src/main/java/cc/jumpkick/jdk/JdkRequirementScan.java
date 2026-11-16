// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JdkTable;
import cc.jumpkick.model.ToolchainSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The JDK requirement a project's {@code jk.toml} states ({@code jdk = { …, required = true }}), read
 * by the line scanner for the callers that choose a JDK without the full parser: the shell hook,
 * {@code jk jdk home}, the launch-time home. The module's table when it says {@code required}, else
 * its workspace root's: a member's table never loosens the root's requirement.
 */
public final class JdkRequirementScan {

    private JdkRequirementScan() {}

    /** The requirement as a spec with only its required axes, or null when none is stated. */
    public static @Nullable ToolchainSpec of(@Nullable Path projectDir) {
        if (projectDir == null) return null;
        List<Path> manifests = new ArrayList<>();
        manifests.add(ManifestPaths.manifestIn(projectDir));
        Optional<Path> root = WorkspaceScan.findRoot(projectDir);
        if (root.isPresent() && !root.get().equals(projectDir)) manifests.add(ManifestPaths.manifestIn(root.get()));
        for (Path manifest : manifests) {
            if (!Files.isRegularFile(manifest)) continue;
            TomlScan scan = TomlScan.scan(manifest, "jdk.vendor", "jdk.version", "jdk.required");
            if (!EnvValues.parseBool(scan.get("jdk.required")).orElse(false)) continue;
            try {
                return JdkTable.spec(scan.get("jdk.vendor"), scan.get("jdk.version"), true);
            } catch (IllegalArgumentException unreadable) {
                return null; // the parser refuses it with the reason; nothing to hold a JDK to here
            }
        }
        return null;
    }
}
