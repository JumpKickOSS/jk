// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A command no registry entry names, tried as one a plugin of the project declares: looked up in
 * the engine, run there, and its answer printed in the envelope every other command gets.
 */
final class PluginCommandDispatch {

    private PluginCommandDispatch() {}

    /**
     * Attempt {@code command} as a plugin-declared command of the project in {@code dir}. Returns
     * the exit code when a plugin owned and ran it, else {@code null} (no jk.toml, no reachable
     * engine, no owning plugin — the unknown-command error follows).
     *
     * <p>Beside a {@code ~/.jk/config.toml} that does not parse, the command is not looked up at all:
     * a {@code [plugins]} pin there may be what declares it, and the lookup parses the project with
     * the file, so the file and its position are named instead ({@link #unreadableUserConfig}) —
     * the same answer whether or not an engine is running.
     */
    static @Nullable Integer tryRun(Path dir, String command, List<String> args) {
        if (!Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))) return null;
        Integer unreadable = unreadableUserConfig(command);
        if (unreadable != null) return unreadable;
        return run(dir, command, args);
    }

    /**
     * {@link Exit#CONFIG} with the file's position when {@code ~/.jk/config.toml} does not parse,
     * else {@code null}.
     */
    private static @Nullable Integer unreadableUserConfig(String command) {
        Optional<UserConfigParseException> problem = GlobalConfig.userConfigProblem();
        if (problem.isEmpty()) return null;
        CommandWedge.printFail(
                command,
                "not a jk command, and the plugins that may declare it cannot be read: "
                        + problem.get().getMessage());
        return Exit.CONFIG;
    }

    private static @Nullable Integer run(Path dir, String command, List<String> args) {
        try {
            var paths = EnginePaths.current();
            if (!EngineProbe.ping(EnginePaths.activeSocket(paths))) return null;
            PluginCommandReport report = EngineClient.pluginCommand(paths, dir, JkDirs.cache(), command, args);

            if (!report.found()) return null;
            return printReport(command, args, report);
        } catch (Exception e) {
            // Best-effort: the caller reports an unknown command, or names config.toml when
            // that file is why the command could not be looked up.
            return null;
        }
    }

    /**
     * Prints a plugin command's answer and returns its exit: the command's output lines first, then
     * the failure when it failed, exiting with the plugin's own code — or 1 when a failed run
     * reports 0, so a failure never exits clean.
     */
    static int printReport(String command, List<String> args, PluginCommandReport report) {
        // A plugin command is a leaf command: same envelope, same close after the failure
        // wedge, so its output is spaced like every other command's. Its raw args are
        // forwarded to the engine unparsed, so honor -O/--output (and JK_OUTPUT) here the
        // way registry commands do at dispatch — machine consumers of a plugin command must
        // not get the envelope or the PlainAscii rewrite.
        CliOutput.beginCommand(argsAskJson(args));
        try {
            for (String line : report.output()) CliOutput.out(line);
            if (report.error() != null) {
                CommandWedge.printFail(command, report.error());
                return report.exit() != 0 ? report.exit() : 1;
            }
            return report.exit();
        } finally {
            CliOutput.closeEnvelope();
        }
    }

    /**
     * {@code -O json} / {@code --output=jsonl} scanned out of a plugin command's raw arg list
     * (never parsed client-side), falling back to {@code JK_OUTPUT} via the same resolution
     * registry commands use.
     */
    static boolean argsAskJson(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            String v = null;
            if (a.equals("-O") || a.equals("--output")) {
                v = i + 1 < args.size() ? args.get(i + 1) : null;
            } else if (a.startsWith("--output=")) {
                v = a.substring("--output=".length());
            }
            if (v != null) return GlobalOptions.outputIsJson(v);
        }
        return GlobalOptions.outputIsJson((String) null); // JK_OUTPUT fallback
    }
}
