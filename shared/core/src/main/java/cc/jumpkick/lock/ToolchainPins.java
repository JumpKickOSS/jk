// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.TomlScan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;

/**
 * The workspace lock's {@code [jdk]} / {@code [graal]} toolchain pins, read by line scan — not
 * {@link LockfileReader} — because the lockfile can be large and callers include the per-prompt
 * shell hook. A pin missing either field reads as absent, never as a partial pin.
 */
public record ToolchainPins(Lockfile.JdkPin jdk, Lockfile.GraalPin graal) {

    public static final ToolchainPins NONE = new ToolchainPins(null, null);

    /** Pins of the lock owning {@code projectDir} (workspace-aware); {@link #NONE} without a lock. */
    public static ToolchainPins scan(Path projectDir) {
        Path lockPath = LockPaths.lockFile(projectDir);
        if (!Files.isRegularFile(lockPath)) return NONE;
        TomlScan scan = TomlScan.scanScalarHead(lockPath, "jdk.vendor", "jdk.version", "graal.vendor", "graal.version");
        return new ToolchainPins(
                pin(scan.get("jdk.vendor"), scan.get("jdk.version"), Lockfile.JdkPin::new),
                pin(scan.get("graal.vendor"), scan.get("graal.version"), Lockfile.GraalPin::new));
    }

    private static <T> T pin(String vendor, String version, BiFunction<String, String, T> factory) {
        if (vendor == null || vendor.isBlank() || version == null || version.isBlank()) return null;
        return factory.apply(vendor, version);
    }
}
