// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cache.PathClients;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.ClientUpdate;
import cc.jumpkick.cli.watch.ProcessTrees;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * A released client older than the version the install now names: jk updated itself while this
 * process was starting or a shell still held the old binary. Spawning its own engine would displace
 * the newer one, whose client would displace it back on the next command. Instead the installed
 * {@code bin/jk} runs the same arguments and this process exits with its code.
 *
 * <p>A long-lived command ({@code jk watch}, {@code jk dev}) that finds the update between passes
 * ({@link ClientUpdate#updatedBetweenPasses}) stops its loop and returns, and the entry point hands
 * its arguments to the installed client the same way, which starts the loop over on the new release.
 */
final class SupersededClient {

    /** Set on the handed-off child, so a client that is still not the newest never loops. */
    static final String ENV = "JK_HANDED_OFF_FROM";

    private SupersededClient() {}

    /**
     * The exit code of {@code args} on the installed client when a long-lived command stopped for
     * an update ({@link ClientUpdate#updatedBetweenPasses}); empty when none did, or the installed
     * client could not be run.
     */
    static OptionalInt handOffAfterCommand(String[] args) {
        if (ClientUpdate.takeHandOff().isEmpty()) return OptionalInt.empty();
        return runInstalled(args);
    }

    /** Run {@code args} on the installed client when this one is superseded; the child's exit code. */
    static OptionalInt handOff(String[] args) {
        if (System.getenv(ENV) != null) return OptionalInt.empty();
        if (ClientUpdate.newerInstall().isEmpty()) return OptionalInt.empty();
        return runInstalled(args);
    }

    /** {@code args} on the installed {@code bin/jk}, this process's terminal handed to it; the child's exit code. */
    private static OptionalInt runInstalled(String[] args) {
        try {
            Path jk = PathClients.pathClient(JkDirs.binDir());
            if (!Files.isRegularFile(jk)) return OptionalInt.empty();
            List<String> command = new ArrayList<>(List.of(jk.toString()));
            command.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put(ENV, JkVersion.VERSION);
            return OptionalInt.of(ProcessTrees.awaitExit(CliOutput.handOffTerminal(pb)));
        } catch (IOException | RuntimeException e) {
            Log.debug("SupersededClient: running here instead", e);
            return OptionalInt.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        }
    }
}
