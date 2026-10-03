// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The lock's {@code [node]} table: the exact Node.js version that built it, the npm bundled with
 * that version, the package manager as {@code name@exact} ({@code null} for npm), and the sha256 of
 * every locked platform's archive, so a machine on another OS verifies against the lock rather than
 * the network.
 *
 * @param sha256 platform key ({@code linux-x64}, …) to lowercase hex, in key order
 */
public record NodePin(
        String version, @Nullable String npm, @Nullable String packageManager, Map<String, String> sha256) {

    public NodePin {
        Objects.requireNonNull(version, "version");
        if (version.isBlank()) throw new IllegalArgumentException("node version is blank");
        npm = npm == null || npm.isBlank() ? null : npm;
        packageManager = packageManager == null || packageManager.isBlank() ? null : packageManager;
        sha256 = sha256 == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(sha256));
    }

    /** The action-key token: {@code node:<exact>}, plus {@code +<pm>@<exact>} when a manager is pinned. */
    public String token() {
        return "node:" + version + (packageManager == null ? "" : "+" + packageManager);
    }
}
