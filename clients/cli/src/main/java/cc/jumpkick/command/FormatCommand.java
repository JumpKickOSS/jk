// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.HostedEvents;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code jk format} — format Java/Kotlin sources (Spotless worker, engine-hosted). Defaults:
 * Palantir + ktfmt KOTLINLANG (4-space / 120-col); {@code --check} exits non-zero if unformatted.
 */
public final class FormatCommand implements CliCommand {

    @Override
    public String name() {
        return "format";
    }

    @Override
    public String description() {
        return "Format Java/Kotlin source code";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Check formatting; fail if unformatted.", "--check"),
                Opt.value("<style>", "Java style: palantir | google | aosp.", "--java-style"),
                Opt.value("<style>", "Kotlin style: kotlinlang | google | meta.", "--kotlin-style"),
                Opt.value("<preset>", "Cross-language preset for both: standard.", "--style"),
                Opt.flag("Shorten FQCNs and add imports (default on).", "--optimize-imports"),
                Opt.flag("Skip FQCN-to-import optimization.", "--no-optimize-imports"),
                Opt.value("<file>", "OpenRewrite YAML config for recipes", "--rewrite-config"));
    }

    /** A format run's summary — the same fields whichever transport ran the plan. */
    private record Outcome(BuildPlanResult result, int changed, int clean, int errors, int total, int workerExit) {}

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        long startMs = System.currentTimeMillis();
        GlobalOptions global = GlobalOptions.from(in);
        boolean check = in.isSet("check");
        Path projectDir = global.workingDir();
        Path buildFile = projectDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Format", "no jk.toml in " + PathDisplay.styledRaw(projectDir)));
            return Exit.CONFIG;
        }
        cc.jumpkick.engine.protocol.ProjectInfo build = BuildCommand.projectInfoOrNull(projectDir);
        if (build == null) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Format", "could not read the project summary (is the engine reachable?)"));
            return Exit.CONFIG;
        }

        // --optimize-imports / --no-optimize-imports / env var / jk.toml / default true
        Boolean cliOptimize = in.isSet("optimize-imports")
                ? Boolean.TRUE
                : in.isSet("no-optimize-imports") ? Boolean.FALSE : envBool("JK_FORMAT_OPTIMIZE_IMPORTS");
        // --rewrite-config / env var
        Path rewriteConfig = in.value("rewrite-config")
                .or(() -> java.util.Optional.ofNullable(System.getenv("JK_FORMAT_REWRITE_CONFIG")))
                .map(Path::of)
                .orElse(null);

        FormatStyles.Resolved styles;
        try {
            styles = FormatStyles.resolve(
                    in.value("java-style").orElse(null),
                    in.value("kotlin-style").orElse(null),
                    in.value("style").orElse(null),
                    cliOptimize,
                    new cc.jumpkick.model.JkBuild.FormatConfig(
                            emptyToNull(build.formatStyle()),
                            emptyToNull(build.formatJava()),
                            emptyToNull(build.formatKotlin()),
                            build.formatOptimizeImports() ? Boolean.TRUE : null));
        } catch (IllegalArgumentException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Format", e.getMessage()));
            return Exit.USAGE;
        }
        // Supplying --rewrite-config implicitly enables optimize-imports when neither
        // flag nor env var said otherwise (so the OpenRewrite plan actually runs).
        boolean optimizeImports = styles.optimizeImports()
                || (rewriteConfig != null && cliOptimize == null && envBool("JK_FORMAT_OPTIMIZE_IMPORTS") == null);

        Path cache = JkDirs.cache();
        boolean animate =
                !check && !global.outputIsJson() && !global.noProgress && BuildPlanConsole.isInteractiveTerminal();

        if (!animate) {
            // Plain path: --check, piped output, CI, --no-progress.
            int[] counts = {0, 0, 0}; // changed, clean, errors
            HostedEvents.FileObserver observer = (path, status, msg, index, total) -> {
                if ("changed".equals(status)) {
                    counts[0]++;
                    if (!global.outputIsJson()) {
                        String mark = Theme.active().isAnsi()
                                ? Theme.colorize(Glyphs.CHECK, Theme.active().success())
                                : Glyphs.CHECK_PLAIN;
                        String rel = Theme.active().isAnsi()
                                ? Theme.colorize(
                                        PathDisplay.of(Path.of(path), projectDir),
                                        Theme.active().path())
                                : PathDisplay.of(Path.of(path), projectDir);
                        CliOutput.out(mark + " " + (check ? "Would format: " : "Formatted: ") + rel);
                    }
                } else if ("error".equals(status)) {
                    counts[2]++;
                    CliOutput.err("  error  " + path + ": " + msg);
                } else {
                    counts[1]++;
                }
            };
            Outcome o;
            try {
                o = runFormatBuildPlan(
                        projectDir,
                        cache,
                        check,
                        styles,
                        optimizeImports,
                        rewriteConfig,
                        global,
                        observer,
                        chatterListener(global, line -> CliOutput.err("  [formatter] " + line)));
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Format", e.getMessage()));
                return Exit.SOFTWARE;
            }
            if (!o.result().success()) {
                for (BuildPlanResult.Diagnostic d : o.result().errors()) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Format", d.message()));
                }
                return 1;
            }
            if (o.total() == 0) {
                if (!global.outputIsJson())
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Format", "no Java or Kotlin sources found.");
                return 0;
            }
            if (!global.outputIsJson()) {
                String took = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
                if (counts[2] > 0) {
                    String errTail = counts[2] + " error" + (counts[2] == 1 ? "" : "s") + " " + took;
                    cc.jumpkick.cli.tui.CommandWedge.printFail("Format", errTail);
                } else if (counts[0] == 0) {
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Format", "Already formatted " + took);
                } else if (check) {
                    String body = counts[0] + " to format, " + counts[1] + " already clean " + took;
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Format", body);
                } else {
                    String body = "Formatted "
                            + counts[0]
                            + " file"
                            + (counts[0] == 1 ? "" : "s")
                            + (counts[1] > 0 ? ", " + counts[1] + " already clean" : "")
                            + " "
                            + took;
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Format", body);
                }
            }
            return o.workerExit();
        }

        // Animated path — start the TUI *first*, so the spinner is already visible while the plan's
        // collect/resolve steps (I/O) run behind it.
        String subtitle = optimizeImports ? "Examining source files & optimizing imports" : "Examining source files";
        try (CommandManager cm = CommandManager.plan(CliOutput.stdout(), "Format", true)) {
            cm.addTaskLabeled("", "fmt", subtitle);
            cm.stepRunning("", "fmt");

            int[] counts = {0, 0, 0}; // changed, clean, errors
            HostedEvents.FileObserver observer = (path, status, msg, index, total) -> {
                // Advance bar on every file so the scan is visually smooth.
                cm.progress(index, total);
                if ("changed".equals(status)) {
                    counts[0]++;
                    cm.addCompletion(completionLine(path, projectDir));
                } else if ("error".equals(status)) {
                    counts[2]++;
                    cm.writeAbove(Theme.colorize("  error", Theme.active().error()) + "  " + path + ": " + msg);
                } else {
                    counts[1]++;
                }
            };
            Outcome o;
            try {
                o = runFormatBuildPlan(
                        projectDir,
                        cache,
                        false,
                        styles,
                        optimizeImports,
                        rewriteConfig,
                        global,
                        observer,
                        chatterListener(global, line -> cm.writeAbove("  [formatter] " + line)));
            } catch (IOException e) {
                cm.finishBuildPlanFailure(String.valueOf(e.getMessage()));
                return Exit.SOFTWARE;
            }
            if (!o.result().success()) {
                for (BuildPlanResult.Diagnostic d : o.result().errors()) {
                    cm.writeAbove(Theme.colorize("  error", Theme.active().error()) + "  " + d.message());
                }
                cm.finishBuildPlanFailure("format failed");
                return 1;
            }
            if (o.total() == 0) {
                cm.stepDone("", "fmt", true);
                cm.finishBuildPlanSuccess("no sources found");
                return 0;
            }

            cm.stepDone("", "fmt", counts[2] == 0);
            String took = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
            if (counts[2] > 0) {
                String errTail = counts[2] + " error" + (counts[2] == 1 ? "" : "s");
                cm.finishBuildPlanFailure(errTail);
            } else if (counts[0] == 0) {
                // Nothing needed formatting.
                cm.finishBuildPlanSuccess(
                        Theme.colorize("Already formatted", Theme.active().success()) + " " + took);
            } else {
                // N formatted, M already clean.
                String formatted = Theme.colorize("Formatted", Theme.active().success())
                        + " "
                        + counts[0]
                        + " file"
                        + (counts[0] == 1 ? "" : "s");
                String clean = counts[1] > 0 ? ", " + counts[1] + " already clean" : "";
                cm.finishBuildPlanSuccess(formatted + clean + " " + took);
            }
            return o.workerExit();
        }
    }

    /**
     * Run the shared {@code FormatPlans} plan — engine-hosted normally, in-process under {@link
     * Engine-hosted format — driving the same {@code observer}. {@code listener}
     * receives the standard plan events (only worker passthrough chatter is rendered from it).
     */
    private static Outcome runFormatBuildPlan(
            Path projectDir,
            Path cache,
            boolean check,
            FormatStyles.Resolved styles,
            boolean optimizeImports,
            Path rewriteConfig,
            GlobalOptions global,
            HostedEvents.FileObserver observer,
            BuildPlanListener listener)
            throws IOException {

        var session = cc.jumpkick.config.SessionContext.current();
        var outcome = cc.jumpkick.cli.engine.EngineClient.runFormat(
                cc.jumpkick.engine.EnginePaths.current(),
                new cc.jumpkick.cli.engine.EngineClient.FormatRequest(
                        projectDir,
                        cache,
                        check,
                        styles.java(),
                        styles.kotlin(),
                        optimizeImports,
                        rewriteConfig,
                        session.offline(),
                        global.verbose),
                steps -> listener,
                observer);
        return new Outcome(
                outcome.result(),
                outcome.changed(),
                outcome.clean(),
                outcome.errors(),
                outcome.total(),
                outcome.workerExit());
    }

    /** A plan listener that surfaces the worker's passthrough chatter under {@code --verbose}. */
    private static BuildPlanListener chatterListener(GlobalOptions global, Consumer<String> sink) {
        return new BuildPlanListener() {
            @Override
            public void output(String step, String line) {
                if (global.verbose) sink.accept(line);
            }
        };
    }

    /** Format a single completion line: {@code ✓ path/to/File.java}. */
    private static String completionLine(String absPath, Path projectDir) {
        Theme t = Theme.active();
        return Theme.colorize(Glyphs.CHECK, t.success())
                + " "
                + Theme.colorize(PathDisplay.of(Path.of(absPath), projectDir), t.path());
    }

    /** Read an env var as a Boolean; returns null when absent or empty. */
    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Boolean envBool(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return null;
        return Boolean.parseBoolean(v.trim());
    }
}
