// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The order a checkout install puts jk's own modules in place: the PATH client before the engine,
 * and no engine past a released PATH client the pass did not replace.
 */
final class InstallOrder {

    private InstallOrder() {}

    /** How a module the pass did not describe is described. */
    @FunctionalInterface
    interface Describe {
        ProjectInfo of(Path module) throws IOException;
    }

    /**
     * {@code modules} with their descriptions ({@code known}'s, else {@code describe}'s) in install
     * order ({@link #rank}): an engine pointer that moves past a released client the pass then fails
     * to replace leaves that client handing every command to itself.
     */
    static List<Map.Entry<Path, ProjectInfo>> ordered(
            Collection<Path> modules, Map<Path, ProjectInfo> known, Describe describe) throws IOException {
        List<Map.Entry<Path, ProjectInfo>> order = new ArrayList<>();
        for (Path mod : modules) {
            ProjectInfo info = known.get(mod);
            order.add(Map.entry(mod, info != null ? info : describe.of(mod)));
        }
        order.sort(Comparator.comparingInt(e -> rank(e.getValue())));
        return order;
    }

    /** Install order within a pass: the PATH client first, every other module next, jk's engine last. */
    static int rank(ProjectInfo info) {
        if (!info.productBin().isBlank()) return 0;
        return info.productLib().isBlank() ? 1 : 2;
    }

    /**
     * Refuse to move the engine pointer to {@code engineVersion} when the PATH client is a released
     * jk older than it that this pass did not replace (no native client built, or the client module
     * not selected): that client would hand every command to itself and refuse the newer engine.
     */
    static void refuseOlderPathClient(Path binDir, String engineVersion, PathClientVersion probe) throws IOException {
        Path client = EngineInstall.pathClient(binDir);
        if (!Files.isRegularFile(client)) return;
        Optional<String> pathVersion = probe.of(client);
        if (pathVersion.isEmpty() || !strandedBy(pathVersion.get(), engineVersion)) return;
        throw new IOException("not installing engine " + engineVersion + ": " + PathDisplay.of(client) + " is jk "
                + pathVersion.get() + ", an older release this install did not replace; build the native"
                + " client (a GraalVM JDK) and install again, or install that release with `jk self update`");
    }

    /** True when a released PATH client {@code pathVersion} is older than {@code engineVersion}. */
    static boolean strandedBy(String pathVersion, String engineVersion) {
        return !pathVersion.endsWith("-SNAPSHOT") && Versions.compare(pathVersion, engineVersion) < 0;
    }

    /** What a PATH client says its version is; empty when it does not say. */
    @FunctionalInterface
    interface PathClientVersion {
        Optional<String> of(Path client);

        /** {@code <client> --version}, handed off nowhere and asked for no update; its first line's version. */
        PathClientVersion ASK = client -> {
            ProcessBuilder pb =
                    new ProcessBuilder(client.toString(), "--version", "--no-ansi").redirectErrorStream(true);
            pb.environment().put("JK_HANDED_OFF_FROM", JkVersion.VERSION);
            pb.environment().put("JK_SELF_UPDATE", "never");
            String out = Processes.outputIfOk(pb, Duration.ofSeconds(30));
            String first = out == null ? "" : out.lines().findFirst().orElse("").strip();
            return first.startsWith("jk ") ? Optional.of(first.substring(3).strip()) : Optional.empty();
        };
    }
}
