// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Runs the same core pipeline ({@code BuildPipelines.coreBuilder}) as {@code jk build}, in
 * {@code testOnly} mode: parse → sync → jdk → compile (Kotlin and/or Java, main and test) →
 * resources → compile-test → run-tests, stopping short of packaging a jar. Sharing the pipeline
 * means Kotlin test sources compile and run exactly as they do under {@code jk build} — no
 * separate, Java-only test path to keep in sync.
 *
 * <p>The test-runner's JSONL event stream bridges into the pipeline's progress bar (the same {@code
 * ProgressBarListener} {@code jk compile}/{@code jk build} use): each completion ticks the
 * numerator, each failure becomes a {@code ctx.error}, discovery grows the denominator.
 */
public final class TestCommand implements CliCommand {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String description() {
        return "Compile and run tests";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>(List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<N>", "Test-runner JVMs to fork in parallel. Default 1.", "-w", "--workers"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.value(
                        "<git-ref>",
                        "Test only modules (and dependents) changed since this git ref.",
                        "--affected-since"),
                Opt.value(
                        "<sel>",
                        "Test only selected modules (comma list, globs, braces). Intersects with --affected-since.",
                        "--modules")));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    String profileName;
    Integer workers;
    Path cacheDir;
    Path jdksDir;
    GlobalOptions global;
    String affectedSince;
    String modulesSpec;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.affectedSince = in.value("affected-since").orElse(null);
        this.modulesSpec = in.value("modules").orElse(null);
        this.global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "test").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        // No jk.lock guard: the pipeline's parse-build step resolves the lock on
        // first run and re-locks when jk.toml changed — same as `jk build`/`run`.

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        int workerCount = workers != null && workers > 0 ? workers : 1;

        // Selective tests: --modules and/or --affected-since (intersection when both).
        if ((affectedSince != null && !affectedSince.isBlank())
                || (modulesSpec != null && !modulesSpec.isBlank())) {
            cc.jumpkick.model.JkBuild entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
            var selected = cc.jumpkick.config.ModuleSelection.resolveOptional(
                    dir, entry, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                CliOutput.err("jk test: " + selected.errorMessage());
                return Exit.CONFIG;
            }
            if (selected != null && selected.moduleDirs().isEmpty()) {
                CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                        cc.jumpkick.cli.tui.Glyphs.CHECK,
                        "Test",
                        cc.jumpkick.config.GlobalConfig.nerdfont(),
                        "nothing selected for tests"));
                return 0;
            }
            if (selected != null && entry.isWorkspaceRoot()) {
                return runWorkspaceTests(dir, entry, cache, workerCount, selected.moduleDirs());
            }
            if (selected != null && !selected.moduleDirs().contains(dir.toAbsolutePath().normalize())) {
                CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                        cc.jumpkick.cli.tui.Glyphs.CHECK,
                        "Test",
                        cc.jumpkick.config.GlobalConfig.nerdfont(),
                        "nothing selected for tests"));
                return 0;
            }
        }

        PipelineResult result;
        TestSummary testResult;
                // Engine-hosted (Step 3): the wire has no real Pipeline to attach a console listener to
        // ahead of time, so the listener is chosen once the step list arrives over the socket —
        // see EngineBuildListenerAdapter.runTest. testResultHolder is populated (if the run-tests
        // step actually ran) before the terminal pipeline-finish reaches that listener, exactly
        // mirroring how pipeline.get(TEST_RESULT) is already populated by the in-process path above.
        TestSummary[] testResultHolder = new TestSummary[1];
        ConsoleSpec spec = new ConsoleSpec(
                "Test", r -> testSummary(testResultHolder[0], r), r -> testFailureMessage(testResultHolder[0], r));
        String module = BuildCommand.buildTarget(buildFile, dir);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runTest(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.TestRequest(
                            dir,
                            cache,
                            jdksDir,
                            workerCount,
                            profileName,
                            global.verbose,
                            // Global flags are consumed into the session before dispatch —
                            // the session (not the Invocation) is their authority, exactly as
                            // BuildCommand's request wiring reads them.
                            cc.jumpkick.config.SessionContext.current().offline(),
                            cc.jumpkick.config.SessionContext.current().force()),
                    steps -> {
                        var console = PipelineConsole.chooseConsoleListener(steps, mode, spec, module);
                        var timeline = cc.jumpkick.cli.run.ChromeTimelineListener.forProject(dir, module);
                        return cc.jumpkick.cli.run.CompositePipelineListener.of(console, timeline);
                    },
                    testResultHolder);
        } catch (IOException e) {
            CliOutput.err("jk test: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        testResult = testResultHolder[0];

        if (result.success()) return 0;
        // Test failures get exit 4; compile / launcher errors are exit 1.
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    /**
     * Workspace selective tests: reuse the workspace build engine path with a dirty-module set and
     * skip packaging (test-only pipelines).
     */
    private int runWorkspaceTests(
            Path entryDir,
            cc.jumpkick.model.JkBuild entryBuild,
            Path cache,
            int workerCount,
            java.util.Set<Path> dirtyDirs)
            throws IOException, InterruptedException {
        // For v1, run sequential tests on each affected module via runTest.
        int worst = 0;
        for (Path mod : dirtyDirs) {
            TestSummary[] testResultHolder = new TestSummary[1];
            ConsoleSpec spec = new ConsoleSpec(
                    "Test",
                    r -> testSummary(testResultHolder[0], r),
                    r -> testFailureMessage(testResultHolder[0], r));
            String module = BuildCommand.buildTarget(mod.resolve("jk.toml"), mod);
            PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
            PipelineResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runTest(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.TestRequest(
                                mod,
                                cache,
                                jdksDir,
                                workerCount,
                                profileName,
                                global.verbose,
                                cc.jumpkick.config.SessionContext.current().offline(),
                                cc.jumpkick.config.SessionContext.current().force()),
                        steps -> {
                            var console = PipelineConsole.chooseConsoleListener(steps, mode, spec, module);
                            var timeline = cc.jumpkick.cli.run.ChromeTimelineListener.forProject(mod, module);
                            return cc.jumpkick.cli.run.CompositePipelineListener.of(console, timeline);
                        },
                        testResultHolder);
            } catch (IOException e) {
                CliOutput.err("jk test: " + mod + ": " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!result.success()) {
                if (testResultHolder[0] != null && !testResultHolder[0].allPassed()) worst = 4;
                else if (worst == 0) worst = 1;
            }
        }
        return worst;
    }

    /**
     * Success result line (sans the leading ✓): {@code Passed N tests in 32s}, or {@code No tests in
     * <t>} for a project with no test sources. Takes the resolved {@link TestSummary}
     * directly (rather than a {@code Pipeline} to look it up from) so both the in-process path (which
     * reads it off {@code pipeline.get(TEST_RESULT)}) and the engine-hosted path (which has no real
     * {@code Pipeline}, only a wire-populated holder) share this one rendering method.
     */
    static String testSummary(TestSummary testResult, PipelineResult result) {
        if (testResult == null || testResult.total() == 0) return "No tests";
        long total = testResult.total();
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s");
    }

    static String testFailureMessage(TestSummary testResult, PipelineResult result) {
        return (testResult != null && !testResult.allPassed()) ? "Tests failed" : "Build failed";
    }
}
