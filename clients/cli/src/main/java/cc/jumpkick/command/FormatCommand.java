// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
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
 * Palantir + ktfmt KOTLINLANG (4-space / 120-col); Java also runs importOrder + removeUnusedImports
 * before the style step (each toggleable); {@code --check} exits non-zero if unformatted.
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
                Opt.flag("Sort imports (default on).", "--import-order"),
                Opt.flag("Skip import sorting.", "--no-import-order"),
                Opt.flag("Remove unused imports (default on).", "--remove-unused-imports"),
                Opt.flag("Keep unused imports.", "--no-remove-unused-imports"),
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

        // Hygiene toggles: --flag / --no-flag / env var / jk.toml / default true
        Boolean cliOptimize = triFlag(in, "optimize-imports", "no-optimize-imports", "JK_FORMAT_OPTIMIZE_IMPORTS");
        Boolean cliImportOrder = triFlag(in, "import-order", "no-import-order", "JK_FORMAT_IMPORT_ORDER");
        Boolean cliRemoveUnused =
                triFlag(in, "remove-unused-imports", "no-remove-unused-imports", "JK_FORMAT_REMOVE_UNUSED_IMPORTS");
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
                    cliImportOrder,
                    cliRemoveUnused,
                    new cc.jumpkick.model.JkBuild.FormatConfig(
                            emptyToNull(build.formatStyle()),
                            emptyToNull(build.formatJava()),
                            emptyToNull(build.formatKotlin()),
                            build.formatOptimizeImports(),
                            build.formatImportOrder(),
                            build.formatRemoveUnusedImports()));
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
                        // Under --check a changed file is a finding, not an accomplishment: the
                        // command is about to exit non-zero *because* of these lines, so they must
                        // not wear the success glyph (JK-1670).
                        String mark = check
                                ? (Theme.active().isAnsi()
                                        ? Theme.colorize(
                                                Glyphs.CROSS, Theme.active().warning())
                                        : Glyphs.CROSS_PLAIN)
                                : (Theme.active().isAnsi()
                                        ? Theme.colorize(
                                                Glyphs.CHECK, Theme.active().success())
                                        : Glyphs.CHECK_PLAIN);
                        String rel = Theme.active().isAnsi()
                                ? Theme.colorize(
                                        PathDisplay.of(Path.of(path), projectDir),
                                        Theme.active().path())
                                : PathDisplay.of(Path.of(path), projectDir);
                        CliOutput.out(mark + " " + (check ? "unformatted: " : "Formatted: ") + rel);
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
                        styles.importOrder(),
                        styles.removeUnusedImports(),
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
                Summary summary = summarize(check, counts[0], counts[1], counts[2], took);
                if (summary.failed()) {
                    cc.jumpkick.cli.tui.CommandWedge.printFail("Format", summary.body());
                } else {
                    cc.jumpkick.cli.tui.CommandWedge.printOk("Format", summary.body());
                }
            }
            return o.workerExit();
        }

        // Animated path — start the TUI *first*, so the spinner is already visible while the plan's
        // collect/resolve steps (I/O) run behind it.
        String subtitle = "Formatting files…";
        try (CommandManager cm = CommandManager.plan(CliOutput.stdout(), "Format", true)) {
            cm.addTaskLabeled("", "fmt", subtitle);
            cm.stepRunning("", "fmt", "Formatting files…");

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
                        styles.importOrder(),
                        styles.removeUnusedImports(),
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
                cm.stepDone("", "fmt", true, "Formatting files…");
                cm.finishBuildPlanSuccess("no sources found");
                return 0;
            }

            cm.stepDone("", "fmt", counts[2] == 0, "Formatting files…");
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
            boolean importOrder,
            boolean removeUnusedImports,
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
                        importOrder,
                        removeUnusedImports,
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

    /**
     * CLI tri-state for a yes/no flag pair: {@code --name} → true, {@code --no-name} → false,
     * otherwise the env var (if set), otherwise {@code null} (fall through to toml / default).
     */
    private static Boolean triFlag(Invocation in, String on, String off, String envVar) {
        if (in.isSet(on)) return Boolean.TRUE;
        if (in.isSet(off)) return Boolean.FALSE;
        return envBool(envVar);
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

    /** The closing wedge: its text, and whether it renders as a failure. */
    record Summary(String body, boolean failed) {}

    /**
     * The run's closing wedge.
     *
     * <p>{@code --check} answers a yes/no question and exits 1 on drift, so drift renders as a
     * failure and names the command that fixes it — a green wedge there sends a contributor who
     * ran it locally to a red CI job with no idea why (JK-1670). Without {@code --check},
     * reformatting files is work done, not a problem.
     */
    static Summary summarize(boolean check, int changed, int clean, int errors, String took) {
        if (errors > 0) {
            return new Summary(errors + " error" + (errors == 1 ? "" : "s") + " " + took, true);
        }
        if (changed == 0) {
            return new Summary("Already formatted " + took, false);
        }
        if (check) {
            return new Summary(
                    changed + " file" + (changed == 1 ? "" : "s") + " unformatted, " + clean
                            + " already clean — run `jk format` " + took,
                    true);
        }
        return new Summary(
                "Formatted " + changed + " file" + (changed == 1 ? "" : "s")
                        + (clean > 0 ? ", " + clean + " already clean" : "") + " " + took,
                false);
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
