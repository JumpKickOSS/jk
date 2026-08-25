// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.config.FormatStyles;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
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
import java.util.Optional;
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

    /**
     * The per-file status for a file OpenRewrite could not parse: Spotless formatted it, the
     * import-shortening pass never ran on it, and it is neither clean nor an error.
     */
    private static final String UNPARSEABLE = "unparseable";

    /**
     * What {@code jk format} returns when the <em>plan</em> failed — the format did not run to
     * completion. Since JK-2474 that includes a worker that died mid-run, which the engine now
     * catches by reconciling its per-file count against the file total.
     *
     * <p>Deliberately not {@code 1}: {@code 1} is {@code --check}'s drift code, and a script that
     * cannot tell "your files need formatting" from "the formatter died" is exactly the conflation
     * {@link Exit} exists to end. Deliberately not the worker's own exit either — a raw {@code 139}
     * from a SIGSEGV or {@code 137} from an OOM-kill is outside jk's vocabulary, and the engine
     * refuses to publish one on a successful plan, so {@code o.workerExit()} below is only ever
     * reached with a {@code 0} or a {@code 1}.
     */
    static final int PLAN_FAILED = Exit.SOFTWARE;

    /** A format run's summary — the same fields whichever transport ran the plan. */
    private record Outcome(BuildPlanResult result, int changed, int clean, int errors, int total, int workerExit) {}

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        long startMs = System.currentTimeMillis();
        GlobalOptions global = GlobalOptions.from(in);
        boolean check = in.isSet("check");
        Path projectDir = global.workingDir();
        Path buildFile = projectDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(buildFile)) {
            CommandWedge.printFail("Format", "no jk.toml in " + PathDisplay.styledRaw(projectDir));
            return Exit.CONFIG;
        }
        ProjectInfo build = BuildCommand.projectInfoOrNull(projectDir);
        if (build == null) {
            CommandWedge.printFail("Format", "could not read the project summary (is the engine reachable?)");
            return Exit.CONFIG;
        }

        // Hygiene toggles: --flag / --no-flag / env var / jk.toml / default true
        Boolean cliOptimize = triFlag(in, "optimize-imports", "no-optimize-imports", "JK_FORMAT_OPTIMIZE_IMPORTS");
        Boolean cliImportOrder = triFlag(in, "import-order", "no-import-order", "JK_FORMAT_IMPORT_ORDER");
        Boolean cliRemoveUnused =
                triFlag(in, "remove-unused-imports", "no-remove-unused-imports", "JK_FORMAT_REMOVE_UNUSED_IMPORTS");
        // --rewrite-config / env var
        Path rewriteConfig = in.value("rewrite-config")
                .or(() -> Optional.ofNullable(System.getenv("JK_FORMAT_REWRITE_CONFIG")))
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
                    new JkBuild.FormatConfig(
                            emptyToNull(build.formatStyle()),
                            emptyToNull(build.formatJava()),
                            emptyToNull(build.formatKotlin()),
                            build.formatOptimizeImports(),
                            build.formatImportOrder(),
                            build.formatRemoveUnusedImports()));
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Format", e.getMessage());
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
            int[] counts = {0, 0, 0, 0}; // changed, clean, errors, unparseable
            HostedEvents.FileObserver observer = (path, status, msg, index, total) -> {
                if ("changed".equals(status)) {
                    counts[0]++;
                    if (!global.outputIsJson()) {
                        // Under --check a changed file is a finding, not an accomplishment: the
                        // command is about to exit non-zero *because* of these lines, so they must
                        // not wear the success glyph.
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
                } else if (UNPARSEABLE.equals(status)) {
                    // Not a failure and not an accomplishment: OpenRewrite could not parse the file,
                    // so it was Spotless-formatted but never import-shortened. It used to be counted
                    // as clean and printed as nothing at all, which is why nobody knew.
                    counts[3]++;
                    if (!global.outputIsJson()) CliOutput.out(unparseableLine(path, projectDir));
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
                CommandWedge.printFail("Format", e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!o.result().success()) {
                for (BuildPlanResult.Diagnostic d : o.result().errors()) {
                    CommandWedge.printFail("Format", d.message());
                }
                return PLAN_FAILED;
            }
            if (o.total() == 0) {
                if (!global.outputIsJson()) CommandWedge.printOk("Format", "no Java or Kotlin sources found.");
                return 0;
            }
            if (!global.outputIsJson()) {
                String took = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
                Summary summary = summarize(check, counts[0], counts[1], counts[2], counts[3], took);
                if (summary.failed()) {
                    CommandWedge.printFail("Format", summary.body());
                } else {
                    CommandWedge.printOk("Format", summary.body());
                }
            }
            return o.workerExit();
        }

        // Animated path — start the TUI *first*, so the spinner is already visible while the plan's
        // collect/resolve steps (I/O) run behind it.
        String subtitle = "Formatting files…";
        try (JkManager cm = JkManager.plan(CliOutput.stdout(), "Format", true)) {
            cm.addTaskLabeled("", "fmt", subtitle);
            cm.stepRunning("", "fmt", "Formatting files…");

            int[] counts = {0, 0, 0, 0}; // changed, clean, errors, unparseable
            HostedEvents.FileObserver observer = (path, status, msg, index, total) -> {
                // Advance bar on every file so the scan is visually smooth.
                cm.progress(index, total);
                if ("changed".equals(status)) {
                    counts[0]++;
                    cm.addCompletion(completionLine(path, projectDir));
                } else if ("error".equals(status)) {
                    counts[2]++;
                    cm.writeAbove(Theme.colorize("  error", Theme.active().error()) + "  " + path + ": " + msg);
                } else if (UNPARSEABLE.equals(status)) {
                    counts[3]++;
                    cm.writeAbove(unparseableLine(path, projectDir));
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
                return PLAN_FAILED;
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
                        Theme.colorize("Already formatted", Theme.active().success())
                                + unparseableTail(counts[3])
                                + " "
                                + took);
            } else {
                // N formatted, M already clean.
                String formatted = Theme.colorize("Formatted", Theme.active().success())
                        + " "
                        + counts[0]
                        + " file"
                        + (counts[0] == 1 ? "" : "s");
                String clean = counts[1] > 0 ? ", " + counts[1] + " already clean" : "";
                cm.finishBuildPlanSuccess(formatted + clean + unparseableTail(counts[3]) + " " + took);
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

        var session = SessionContext.current();
        var outcome = EngineClient.runFormat(
                EnginePaths.current(),
                new EngineRequests.FormatRequest(
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
     * ran it locally to a red CI job with no idea why. Without {@code --check},
     * reformatting files is work done, not a problem.
     *
     * <p>{@code unparseable} rides along in every branch and changes none of their verdicts. It is
     * a count of files OpenRewrite could not parse, so Spotless formatted them but the
     * import-shortening pass never ran; no edit to the file clears it, so failing the command on it
     * would be a red build with no fix. It is in the wedge because the alternative — what this
     * command did until JK-2477 — was to count them as already clean and say nothing.
     */
    static Summary summarize(boolean check, int changed, int clean, int errors, int unparseable, String took) {
        String skipped = unparseableTail(unparseable);
        if (errors > 0) {
            return new Summary(errors + " error" + (errors == 1 ? "" : "s") + skipped + " " + took, true);
        }
        if (changed == 0) {
            return new Summary("Already formatted" + skipped + " " + took, false);
        }
        if (check) {
            return new Summary(
                    changed + " file" + (changed == 1 ? "" : "s") + " unformatted, " + clean + " already clean"
                            + skipped + " — run `jk format` " + took,
                    true);
        }
        return new Summary(
                "Formatted " + changed + " file" + (changed == 1 ? "" : "s")
                        + (clean > 0 ? ", " + clean + " already clean" : "") + skipped + " " + took,
                false);
    }

    /** The summary's unparseable clause, or nothing at all when there are none. */
    static String unparseableTail(int unparseable) {
        return unparseable == 0 ? "" : ", " + unparseable + " not import-shortened (OpenRewrite cannot parse them)";
    }

    /**
     * One {@code unparseable} finding: {@code ! unparseable: path/to/File.java}.
     *
     * <p>The shape is load-bearing, not decorative. It is what makes the set derivable rather than
     * curated — {@code fqcn-baseline.txt}'s {@code ## unreachable-by-the-formatter} section is
     * regenerated by filtering these lines (see that file's header), so the {@code unparseable: }
     * marker and the plain repo-relative path after it are the interface.
     */
    static String unparseableLine(String absPath, Path projectDir) {
        return Theme.colorize(Glyphs.cross(), Theme.active().warning())
                + " unparseable: "
                + PathDisplay.styled(Path.of(absPath), projectDir);
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
