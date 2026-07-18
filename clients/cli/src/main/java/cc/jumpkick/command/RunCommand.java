// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Project run pipeline (not a {@code CliCommand}): build then exec. {@link ToolRunCommand}
 * delegates here via {@link #runProject}. Preference: <strong>native &gt; shadow jar &gt; plain
 * jar</strong>.
 */
public final class RunCommand {

    List<String> positional = new ArrayList<>();
    Path cacheDirOverride;
    Path jdksDir;
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}. Real {@code jk run} hosts the build on the engine;
     * user-program exec stays here (owns the terminal).
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=TestRunner) — see BuildCommand.
        return Boolean.getBoolean("jk.test.noEngine")
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /** Package-private: {@code jk tool run <dir>} delegates a jk-project directory here. */
    int runProject(Path projectDir, List<String> appArgs) throws IOException, InterruptedException {
        // No client-side jk.toml parse: the engine computes the exec plan (artifact
        // preference, classpath, main-class scan) after the build — thin-client contract.
        Path cache = cacheDir();

        String coord = BuildCommand.buildTarget(projectDir.resolve("jk.toml"), projectDir);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        // In chip modes (AUTO/QUIET) the pipeline settles with the ▶ Exec chip line showing
        // the exec command directly — no second banner line. In VERBOSE/JSON no chip is
        // printed, so printExecBanner runs after the pipeline as before.
        ConsoleSpec spec = new ConsoleSpec(
                "Exec",
                r -> {
                    try {
                        return execTail(projectDir, execPlan(projectDir));
                    } catch (IOException e) {
                        return "Executing";
                    }
                },
                r -> PipelineWedge.coord(coord),
                true,
                true,
                r -> {
                    // The build succeeded but there may be nothing runnable — settle as a failure
                    // (red chip) with a sentence naming the actual problem, not "Failed to exec".
                    if (!r.success()) return null;
                    try {
                        execPlan(projectDir);
                        return null;
                    } catch (EntryPointUnresolvedException e) {
                        return mainIssueSentence(e.issue(), coord);
                    } catch (IOException e) {
                        return null;
                    }
                });

        PipelineResult result;
        cc.jumpkick.run.TestSummary testResult;
        if (engineDisabledForTests()) {
            var o = cc.jumpkick.cli.engine.InProcessEngine.require()
                    .runBuildPipeline(
                            projectDir, cache, jdksDir, buildOpts.skipTests, global.verbose, mode, spec, coord);
            result = o.result();
            testResult = o.testResult();
        } else {
            // Engine-hosted build half (SINGLE_BUILD_REQUEST, skipTests); exec below owns the TTY.
            var session = cc.jumpkick.config.SessionContext.current();
            cc.jumpkick.run.TestSummary[] testResultHolder = new cc.jumpkick.run.TestSummary[1];
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runSingleBuild(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.SingleBuildRequest(
                                projectDir,
                                cache,
                                jdksDir,
                                1,
                                null,
                                buildOpts.skipTests,
                                global.verbose,
                                session.offline(),
                                session.force(),
                                session.variant(),
                                session.clientEnv()),
                        steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, coord),
                        testResultHolder,
                        new String[1]);
            } catch (IOException e) {
                CliOutput.err("jk run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            testResult = testResultHolder[0];
        }
        if (!result.success()) {
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }

        // Exec the engine's plan: the most self-contained artifact (native > shadow > jar),
        // computed engine-side against the just-built outputs. A device artifact (an APK)
        // never forks on the host — the plan names the plugin's deploy command instead.
        List<String> command;
        try {
            cc.jumpkick.engine.protocol.ExecPlan plan = execPlan(projectDir);
            if (!plan.deployCommand().isEmpty()) {
                return dispatchDeployCommand(projectDir, cache, plan.deployCommand(), appArgs, mode);
            }
            command = new ArrayList<>(plan.argv());
        } catch (EntryPointUnresolvedException e) {
            // The chip already settled with this exact failure (spec's softFailure closure ran
            // first and cached the same plan) — VERBOSE/JSON print no chip, so give them the plain
            // text version there instead of leaving the command silent.
            if (mode == PipelineConsole.Mode.VERBOSE || mode == PipelineConsole.Mode.JSON) {
                CliOutput.err("jk run: " + e.getMessage());
            }
            return Exit.DATA_ERR;
        } catch (IOException e) {
            CliOutput.err("jk run: " + e.getMessage());
            return Exit.USAGE;
        }
        if (mode == PipelineConsole.Mode.VERBOSE || mode == PipelineConsole.Mode.JSON) {
            // No chip was printed in these modes — show the banner line as before.
            printExecBanner(projectDir, execPlan(projectDir));
        } else {
            // Chip already settled with exec info; emit the blank separator + color reset.
            CliOutput.err();
            if (Theme.colorEnabled()) {
                CliOutput.errRaw(Ansi.RESET);
                CliOutput.stderr().flush();
            }
        }
        command.addAll(appArgs);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    /**
     * {@code jk run} on a device artifact: dispatch the plugin's declared deploy command over the
     * plugin-command protocol (install + launch happen in the plugin's worker; nothing execs on
     * the host JVM). Output lines stream back as the command's output.
     */
    private int dispatchDeployCommand(
            Path projectDir, Path cache, String command, List<String> appArgs, PipelineConsole.Mode mode)
            throws IOException {
        if (mode != PipelineConsole.Mode.VERBOSE && mode != PipelineConsole.Mode.JSON) {
            CliOutput.err();
        }
        cc.jumpkick.engine.protocol.PluginCommandReport report;
        try {
            report = engineDisabledForTests()
                    ? cc.jumpkick.cli.engine.InProcessEngine.require()
                            .pluginCommand(projectDir, cache, command, appArgs)
                    : cc.jumpkick.cli.engine.EngineClient.pluginCommand(
                            cc.jumpkick.engine.EnginePaths.current(), projectDir, cache, command, appArgs);
        } catch (Exception e) {
            CliOutput.err("jk run: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!report.found()) {
            CliOutput.err("jk run: the packaging plugin declares deploy command `" + command + "` but does not"
                    + " register it");
            return Exit.SOFTWARE;
        }
        if (report.error() != null) {
            CliOutput.err("jk run: " + report.error());
            return 1;
        }
        for (String line : report.output()) CliOutput.out(line);
        return report.exit();
    }

    /**
     * Engine-computed execution plan (artifact preference, RUN classpath, main-class). Memoized so
     * the console's exec-tail and the real exec agree.
     */
    private cc.jumpkick.engine.protocol.ExecPlan execPlan(Path projectDir) throws IOException {
        if (cachedPlan == null) {
            cachedPlan = engineDisabledForTests()
                    ? cc.jumpkick.cli.engine.InProcessEngine.require()
                            .execPlan(projectDir, cacheDir(), "run", null, null)
                    : cc.jumpkick.cli.engine.EngineClient.execPlan(
                            cc.jumpkick.engine.EnginePaths.current(), projectDir, cacheDir(), "run", null, null);
        }
        // Checked on every access: the memoized plan may be an error plan (the console's
        // tail closure swallows the first throw; the exec path must still see it).
        if (cachedPlan.error() != null) {
            if (!cachedPlan.mainIssue().isEmpty()) {
                throw new EntryPointUnresolvedException(cachedPlan.error(), cachedPlan.mainIssue());
            }
            throw new IOException(cachedPlan.error());
        }
        return cachedPlan;
    }

    private cc.jumpkick.engine.protocol.ExecPlan cachedPlan;

    /**
     * The build succeeded but the engine's main-class scan couldn't name an entry point — {@code
     * issue} is {@code "missing"} (nothing found) or {@code "ambiguous"} (several found), per
     * {@link cc.jumpkick.engine.protocol.ExecPlan#mainIssue()}.
     */
    private static final class EntryPointUnresolvedException extends IOException {
        private final String issue;

        EntryPointUnresolvedException(String message, String issue) {
            super(message);
            this.issue = issue;
        }

        String issue() {
            return issue;
        }
    }

    /**
     * {@code Failed to run {coord}. No valid [yellow]main[/] method was specified or detected} (or,
     * for {@code issue = "ambiguous"}, {@code Multiple [yellow]main[/] methods found.}) — the
     * sentence {@link cc.jumpkick.cli.tui.PipelineWedge#failureLineCustom} renders after the red chip.
     */
    private static String mainIssueSentence(String issue, String coord) {
        Theme t = Theme.active();
        String main = Theme.colorize("main", t.highlight());
        String head = Theme.colorize("Failed", t.error()) + " to run " + PipelineWedge.coord(coord) + ". ";
        return head
                + ("ambiguous".equals(issue)
                        ? "Multiple " + main + " methods found."
                        : "No valid " + main + " method was specified or detected");
    }

    /**
     * The styled tail shown in the exec chip line or banner: {@code "[cyan]{jdk}[/]: [yellow]java
     * …[/]"} for JVM, {@code "native binary: [yellow]target/app[/]"} for native. The plan carries
     * the display command; the JDK leaf derives from the plan's javaHome.
     */
    private static String execTail(Path projectDir, cc.jumpkick.engine.protocol.ExecPlan plan) {
        Theme t = Theme.active();
        if (plan.argv().size() == 1) {
            // Native binary — exec'd directly, no JVM.
            Path bin = Path.of(plan.argv().get(0));
            return "native binary: " + Theme.colorize(PathDisplay.of(bin, projectDir), t.highlight());
        }
        Path jdkHome = Path.of(plan.javaHome());
        try {
            jdkHome = jdkHome.toRealPath();
        } catch (IOException ignored) {
        }
        String jdkLeaf = jdkHome.getFileName() != null ? jdkHome.getFileName().toString() : "java";
        return Theme.colorize(jdkLeaf, t.cyan()) + ": " + Theme.colorize(plan.display(), t.shell());
    }

    /**
     * Prints the {@code ▶ Executing …} line to stderr (verbose/JSON modes, where no chip is
     * rendered). Delegates styling to {@link #execTail}.
     */
    private static void printExecBanner(Path projectDir, cc.jumpkick.engine.protocol.ExecPlan plan) {
        Theme t = Theme.active();
        CliOutput.err(
                Theme.colorize(cc.jumpkick.cli.tui.Glyphs.PLAY, t.brightGreen()) + " " + execTail(projectDir, plan));
        CliOutput.err();
        // Reset any lingering SGR state so the program's own output starts from
        // the terminal's default colors (only when we're emitting color at all).
        if (Theme.colorEnabled()) {
            CliOutput.errRaw(Ansi.RESET);
            CliOutput.stderr().flush();
        }
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }
}
