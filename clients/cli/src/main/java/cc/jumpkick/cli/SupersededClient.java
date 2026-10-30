// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.ReleaseArtifacts;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * A released client older than the version the install now names: jk updated itself while this
 * process was starting or a shell still held the old binary. Spawning its own engine would displace
 * the newer one, whose client would displace it back on the next command. Instead the installed
 * {@code bin/jk} runs the same arguments and this process exits with its code.
 */
final class SupersededClient {

    /** Set on the handed-off child, so a client that is still not the newest never loops. */
    static final String ENV = "JK_HANDED_OFF_FROM";

    private SupersededClient() {}

    /** The installed version when it is newer than {@code running}; empty for a development client. */
    static Optional<String> newerInstall(String running, boolean releasedClient, Supplier<Optional<String>> installed) {
        if (!ReleaseArtifacts.applicable(running, releasedClient, false)) return Optional.empty();
        return installed.get().filter(v -> Versions.compare(v, running) > 0);
    }

    /** Run {@code args} on the installed client when this one is superseded; the child's exit code. */
    static OptionalInt handOff(String[] args) {
        if (System.getenv(ENV) != null) return OptionalInt.empty();
        try {
            Optional<String> newer =
                    newerInstall(JkVersion.VERSION, ReleaseArtifacts.releasedClient(), () -> EngineInstall.current()
                            .currentInstall()
                            .map(EngineInstall.Materialized::version));
            if (newer.isEmpty()) return OptionalInt.empty();
            Path jk = EngineInstall.pathClient(JkDirs.binDir());
            if (!Files.isRegularFile(jk)) return OptionalInt.empty();
            List<String> command = new ArrayList<>(List.of(jk.toString()));
            command.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put(ENV, JkVersion.VERSION);
            return OptionalInt.of(CliOutput.handOffTerminal(pb).waitFor());
        } catch (IOException | RuntimeException e) {
            Log.debug("SupersededClient: running here instead", e);
            return OptionalInt.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        }
    }
}
