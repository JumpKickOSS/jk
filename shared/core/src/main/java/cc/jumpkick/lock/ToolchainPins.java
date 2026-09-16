// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.TomlScan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The workspace lock's {@code [jdk]} / {@code [graal]} toolchain pins, read by line scan — not
 * {@link LockfileReader} — because the lockfile can be large and callers include the per-prompt
 * shell hook. A table naming neither a vendor nor a version reads as absent.
 */
public record ToolchainPins(Lockfile.@Nullable JdkPin jdk, Lockfile.@Nullable GraalPin graal) {

    public static final ToolchainPins NONE = new ToolchainPins(null, null);

    private static final String[] FIELDS = {
        "suggested-vendor", "suggested-version", "required-vendor", "required-version"
    };

    /** Pins of the lock owning {@code projectDir} (workspace-aware); {@link #NONE} without a lock. */
    public static ToolchainPins scan(Path projectDir) {
        Path lockPath = LockPaths.lockFile(projectDir);
        if (!Files.isRegularFile(lockPath)) return NONE;
        String[] keys = new String[FIELDS.length * 2];
        for (int i = 0; i < FIELDS.length; i++) {
            keys[i] = "jdk." + FIELDS[i];
            keys[FIELDS.length + i] = "graal." + FIELDS[i];
        }
        TomlScan scan = TomlScan.scanScalarHead(lockPath, keys);
        return new ToolchainPins(pin(scan, "jdk", JdkPin::new), pin(scan, "graal", GraalPin::new));
    }

    private static <T extends ToolchainPin> @Nullable T pin(TomlScan scan, String table, Pins<T> factory) {
        T pin = factory.of(
                ToolchainPin.blankToEmpty(scan.get(table + ".suggested-vendor")),
                ToolchainPin.blankToEmpty(scan.get(table + ".suggested-version")),
                ToolchainPin.blankToEmpty(scan.get(table + ".required-vendor")),
                ToolchainPin.blankToEmpty(scan.get(table + ".required-version")));
        return pin.isEmpty() ? null : pin;
    }

    /** The four-argument constructor shared by {@link JdkPin} and {@link GraalPin}. */
    private interface Pins<T> {
        T of(String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion);
    }
}
