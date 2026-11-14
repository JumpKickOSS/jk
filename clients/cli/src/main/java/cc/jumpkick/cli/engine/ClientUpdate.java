// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Whether jk updated itself to a newer release than this running client. A long-lived command
 * ({@code jk watch}, {@code jk dev}) asks between passes ({@link #updatedBetweenPasses}): this
 * client cannot drive the newer engine, so the command stops its loop and returns, and the entry
 * point runs the same arguments on the installed client ({@link #takeHandOff}).
 */
public final class ClientUpdate {

    /** The release a long-lived command stopped for; null when none did. */
    private static volatile @Nullable String handOffTo;

    private ClientUpdate() {}

    /** The installed version when it is newer than {@code running}; empty for a development client. */
    public static Optional<String> newerInstall(
            String running, boolean releasedClient, Supplier<Optional<String>> installed) {
        if (!ReleasedClient.applicable(running, releasedClient, false)) return Optional.empty();
        return installed.get().filter(v -> Versions.compare(v, running) > 0);
    }

    /** The newer release the install names than this client; empty for a development client or a current one. */
    public static Optional<String> newerInstall() {
        try {
            return newerInstall(JkVersion.VERSION, ReleasedClient.current(), () -> EngineInstall.current()
                    .currentInstall()
                    .map(EngineInstall.Materialized::version));
        } catch (RuntimeException unreadable) {
            Log.debug("ClientUpdate: install unreadable", unreadable);
            return Optional.empty();
        }
    }

    /**
     * Between two passes of a long-lived command: true when the install names a newer release than
     * this client, said once on stderr. The command then stops its loop and returns.
     */
    public static boolean updatedBetweenPasses() {
        return updatedBetweenPasses(newerInstall());
    }

    static boolean updatedBetweenPasses(Optional<String> newer) {
        if (newer.isEmpty()) return false;
        handOffTo = newer.get();
        CliOutput.err("jk was updated to " + newer.get() + "; continuing on it");
        return true;
    }

    /** The release a long-lived command stopped for, cleared; empty when none did. */
    public static Optional<String> takeHandOff() {
        String to = handOffTo;
        handOffTo = null;
        return Optional.ofNullable(to);
    }
}
