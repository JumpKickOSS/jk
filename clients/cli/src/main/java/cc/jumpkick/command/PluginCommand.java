// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.compile.WorkerLib;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code jk plugin …} — first-party worker packaging helpers for self-host / dogfood.
 *
 * <p>{@code install-local} side-loads workspace <strong>thin</strong> PluginMain jars into the
 * local Maven layout, writes a {@code .classpath} sidecar of runtime deps, and
 * hard-links worker + deps into {@code $JK_STORE_DIR/lib/&lt;id&gt;/} for compact launch paths.
 */
public final class PluginCommand extends GroupCommand {

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "First-party plugin worker helpers (install-local, uninstall)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new InstallLocalSub(), new UninstallSub());
    }

    /**
     * {@code jk plugin uninstall <artifactId>} — drop a side-loaded worker: its
     * {@code store/lib/<id>/} hardlink dir (unpins CAS inodes for GC — ) and its
     * {@code repos/local} Maven entries.
     */
    static final class UninstallSub implements CliCommand {

        @Override
        public String name() {
            return "uninstall";
        }

        @Override
        public String description() {
            return "Remove a side-loaded worker from store/lib";
        }

        @Override
        public List<cc.jumpkick.model.command.Param> parameters() {
            return List.of(cc.jumpkick.model.command.Param.of(
                    "artifact-id", cc.jumpkick.model.command.Arity.ONE, "worker artifactId, e.g. jk-test-runner"));
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws Exception {
            String artifactId = in.positionals().get(0);
            boolean isolated = in.value("cache-dir").isPresent();
            Path installRoot = isolated
                    ? in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElseThrow()
                    : JkDirs.store();
            boolean removed = false;
            // The shared lib dir belongs to the global store; leave it alone under --cache-dir.
            if (!isolated && Files.isDirectory(WorkerLib.dir(artifactId))) {
                WorkerLib.remove(artifactId);
                removed = true;
            }
            Path repoDir = installRoot.resolve("repos/local/cc/jumpkick").resolve(artifactId);
            if (Files.isDirectory(repoDir)) {
                try (var walk = Files.walk(repoDir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
                removed = true;
            }
            if (!removed) {
                CommandWedge.printFail("Plugin", "nothing installed for " + artifactId);
                return Exit.CONFIG;
            }
            CommandWedge.printOk("Plugin", "Uninstalled " + artifactId);
            return Exit.SUCCESS;
        }
    }

    /**
     * {@code jk plugin install-local} — copy built PluginMain thin jars + classpath sidecars into
     * the local cache Maven layout used by the engine's worker locator.
     */
    static final class InstallLocalSub implements CliCommand {

        @Override
        public String name() {
            return "install-local";
        }

        @Override
        public String description() {
            return "Side-load workspace plugin jars into local store";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<sel>", "Only these modules (default: all workers)", "-m", "--modules"),
                    Opt.flag("Print what would be installed; write nothing.", "--dry-run"),
                    cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws Exception {
            GlobalOptions global = GlobalOptions.from(in);
            Path dir = global.workingDir();
            Path rootToml = dir.resolve("jk.toml");
            if (!Files.isRegularFile(rootToml)) {
                CommandWedge.printFail("Plugin", "no jk.toml in " + PathDisplay.styledRaw(dir));
                return Exit.CONFIG;
            }

            String modulesSpec = in.value("modules").orElse(null);
            Path cache =
                    in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(JkDirs.cache());
            boolean ambient = !in.value("cache-dir").isPresent();
            Path installRoot = ambient ? JkDirs.store() : cache;
            boolean dryRun = in.isSet("dry-run");

            int lockCode = cc.jumpkick.cli.EnsureFreshLock.ensure(dir, cache, global, "Plugin");
            if (lockCode != 0) return lockCode;

            cc.jumpkick.engine.protocol.PluginInstallLocalAck ack;
            try {
                ack = cc.jumpkick.cli.engine.EngineClient.pluginInstallLocal(
                        cc.jumpkick.engine.EnginePaths.current(),
                        dir,
                        cache,
                        installRoot,
                        modulesSpec,
                        dryRun,
                        ambient);
            } catch (Exception e) {
                CommandWedge.printFail("Plugin", e.getMessage());
                return Exit.SOFTWARE;
            }
            if (ack.error() != null && !ack.error().isBlank()) {
                CommandWedge.printFail("Plugin", ack.error());
                return Exit.CONFIG;
            }
            int n = ack.installed();
            String header = dryRun
                    ? "Would install " + n + " plugin" + (n == 1 ? "" : "s")
                    : "Installed " + n + " plugin" + (n == 1 ? "" : "s");
            List<RichText> children = new ArrayList<>();
            for (String line : ack.lines()) {
                children.add(gavDetail(line));
            }
            if (n > 0 || !children.isEmpty()) {
                CommandWedge.printOkTree("Plugin", header, children);
            }
            for (String m : ack.missing()) {
                CliOutput.err("  missing jar: " + m + " — run `jk build` first");
            }
            if (ack.skipped() > 0 && ack.installed() == 0) return Exit.FAILURE;
            return 0;
        }

        /** Prefer themed {@code group:artifact:version}; fall back to plain text. */
        private static RichText gavDetail(String line) {
            if (line == null || line.isBlank()) return RichText.empty();
            String[] parts = line.split(":", 3);
            if (parts.length == 3
                    && !parts[0].isBlank()
                    && !parts[1].isBlank()
                    && !parts[2].isBlank()
                    && !parts[0].contains(" ")) {
                return Coord.gav(parts[0], parts[1], parts[2]).text();
            }
            return RichText.plain(line);
        }
    }
}
