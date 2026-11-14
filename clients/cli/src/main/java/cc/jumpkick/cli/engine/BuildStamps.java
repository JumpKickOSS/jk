// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Which commit the client and the running engine were built from, as {@code jk --version -v},
 * {@code jk engine status} and {@code jk doctor} print it, so a client and engine from different
 * builds are told apart.
 */
public final class BuildStamps {

    private BuildStamps() {}

    /**
     * The commit the running client was built from and where it was read: {@code recorded} when the
     * client carries none itself (a snapshot's native image) and the install recorded it beside
     * this very binary, in the engine pointer.
     */
    public record Client(@Nullable String commit, boolean recorded) {

        /** {@code 0.51.0 · commit 4ee07400a592 (recorded at install)}. */
        public String describe(String version) {
            return BuildStamps.describe(version, commit) + (recorded ? " (recorded at install)" : "");
        }
    }

    /** The running client's commit: its own stamp, else the one its install recorded, else none. */
    public static Client client() {
        String own = BuildIdentity.builtFrom();
        if (own != null) return new Client(own, false);
        return recordedForThisBinary().map(c -> new Client(c, true)).orElse(new Client(null, false));
    }

    /** The commit the pointer records for the client, when that client is the running binary. */
    private static Optional<String> recordedForThisBinary() {
        try {
            Optional<String> exe = ProcessHandle.current().info().command();
            if (exe.isEmpty()) return Optional.empty();
            return EngineInstall.current().recordedClientCommit(Hashing.sha256Hex(Path.of(exe.get())));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** {@code 0.51.0 · commit 4ee07400a592}: a version and the commit it was built from. */
    public static String describe(String version, @Nullable String stamp) {
        return version + " · commit " + BuildIdentity.describeCommit(stamp);
    }

    /**
     * Whether the engine was built from another commit than the client: both stamped and different.
     * An unstamped side (a build outside a checkout, an engine too old to report) is no evidence.
     */
    public static boolean differ(@Nullable String client, @Nullable String engine) {
        if (client == null || client.isBlank() || engine == null || engine.isBlank()) return false;
        return !client.equals(engine);
    }

    /** The lines {@code jk --version -v} prints after {@code jk <version>}. */
    public static List<String> versionLines(Client client, Optional<EngineProbe.Status> engine) {
        String clientLine = "  client  " + client.describe(JkVersion.VERSION);
        if (engine.isEmpty()) return List.of(clientLine, "  engine  not running");
        EngineProbe.Status s = engine.get();
        String engineLine = "  engine  " + describe(s.version(), s.engineCommit()) + " · pid " + s.pid();
        if (differ(client.commit(), s.engineCommit())) engineLine += " (not the client's build)";
        return List.of(clientLine, engineLine);
    }
}
