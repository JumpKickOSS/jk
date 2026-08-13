// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Project run plan (not a {@code CliCommand}): build then exec. {@link ToolRunCommand}
 * delegates here via {@link #runProject}. Preference: <strong>native &gt; assembly jar &gt; plain
 * jar</strong>.
 *
 * <p>Flow: engine-hosted build phase-chain (compile / test when not skipped / package as needed),
 * then a client-side detached {@code java …} (or native binary) with inherited stdio. The settled
 * chrome is a play {@link CommandWedge}: {@code ▶ Run Executing `java -cp … Main`} (or
 * {@code java -jar …}).
 */
public final class RunCommand {

    List<String> positional = new ArrayList<>();
    Path cacheDirOverride;
    Path jdksDir;
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;

    /** Package-private: {@code jk tool run <dir>} delegates a jk-project directory here. */
    int runProject(Path projectDir, List<String> appArgs) throws IOException, InterruptedException {
        // Engine computes the exec plan (artifact preference, classpath, main-class scan)
        // after the build. Workspace roots build the whole graph, then pick a module to run.
        Path cache = cacheDir();

        String coord = BuildCommand.buildTarget(projectDir.resolve("jk.toml"), projectDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        // In chip modes (AUTO/QUIET) the build plan settles as the ▶ Run CommandWedge with
        // "Executing `java …`" — no second banner line. In VERBOSE/JSON no chip is printed, so
        // printExecBanner runs after the plan as before.
        ConsoleSpec spec = new ConsoleSpec(
                "Run",
                r -> {
                    try {
                        return execTail(projectDir, execPlan(projectDir));
                    } catch (IOException e) {
                        return "Executing";
                    }
                },
                r -> Coord.module(coord).renderLine(),
                true,
                true,
                r -> {
                    // The build succeeded but there may be nothing runnable — settle as a failure
                    // (red chip) with a sentence naming the actual problem, not "Failed to run".
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

        BuildPlanResult result;
        cc.jumpkick.run.TestSummary testResult;
        var session = cc.jumpkick.config.SessionContext.current();
        cc.jumpkick.run.TestSummary[] testResultHolder = new cc.jumpkick.run.TestSummary[1];
        try {
            boolean workspace = false;
            try {
                workspace = cc.jumpkick.config.JkBuildParser.parse(projectDir.resolve("jk.toml"))
                        .isWorkspaceRoot();
            } catch (Exception ignored) {
                // fall through to single-module path
            }
            if (workspace) {
                // Build every module (path deps, sibling jars), then execPlan picks the app module.
                var rootBuild = cc.jumpkick.config.JkBuildParser.parse(projectDir.resolve("jk.toml"));
                int jobs = global.jobsEffective();
                // Session variant/clientEnv ride the request like `jk build` at a root does
                // `jk run --release` used to build debug and then exec release artifacts that
                // were never produced.
                var request = new cc.jumpkick.runtime.WorkspaceRequest(
                                projectDir,
                                rootBuild,
                                cache,
                                jdksDir,
                                1,
                                null,
                                buildOpts.skipTests,
                                global.verbose,
                                jobs,
                                null,
                                true,
                                true)
                        .withVariant(session.variant(), session.clientEnv());
                boolean liveWorkspace = mode == BuildPlanConsole.Mode.AUTO
                        && BuildPlanConsole.isInteractiveTerminal()
                        && !global.outputIsJson();
                cc.jumpkick.runtime.WorkspaceResult wr;
                if (liveWorkspace) {
                    // Same live chrome as `jk build` at a root (JK-1201): aggregate bar + module
                    // chips from the engine tracker, completions collapse into the region.
                    wr = runWorkspaceLive(request);
                    if (wr == null) return 1; // failure already settled on the view
                } else {
                    // Quiet / JSON / non-tty: append-only per-module completions (unchanged).
                    java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                    int[] total = {0};
                    var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
                        @Override
                        public void onPlan(java.util.List<cc.jumpkick.runtime.ModulePlan> plan) {
                            total[0] = plan.size();
                        }

                        @Override
                        public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                            String glyph =
                                    o.success() ? cc.jumpkick.cli.tui.Glyphs.CHECK : cc.jumpkick.cli.tui.Glyphs.CROSS;
                            CliOutput.out(glyph + " [" + done.incrementAndGet() + "/" + Math.max(total[0], 1) + "] "
                                    + o.coord());
                        }
                    };
                    wr = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                            cc.jumpkick.engine.EnginePaths.current(), request, listener);
                }
                if (wr != null && !wr.success()) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Run", "workspace build failed"));
                    return 1;
                }
                // Synthetic success result so the exec chip path continues unchanged.
                result = new BuildPlanResult(
                        "workspace", true, java.time.Duration.ZERO, List.of(), List.of(), List.of(), false);
                testResult = null;
            } else {
                // Engine-hosted single-module build (SINGLE_BUILD_REQUEST, skipTests).
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
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, coord),
                        testResultHolder,
                        new String[1]);
                testResult = testResultHolder[0];
            }
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Run", e.getMessage()));
            return Exit.SOFTWARE;
        }

        if (!result.success()) {
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }

        // Exec the engine's plan: the most self-contained artifact (native > assembly > jar),
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
            if (mode == BuildPlanConsole.Mode.VERBOSE || mode == BuildPlanConsole.Mode.JSON) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Run", e.getMessage()));
            }
            return Exit.DATA_ERR;
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Run", e.getMessage()));
            return Exit.USAGE;
        }
        if (mode == BuildPlanConsole.Mode.VERBOSE || mode == BuildPlanConsole.Mode.JSON) {
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
            Path projectDir, Path cache, String command, List<String> appArgs, BuildPlanConsole.Mode mode)
            throws IOException {
        if (mode != BuildPlanConsole.Mode.VERBOSE && mode != BuildPlanConsole.Mode.JSON) {
            CliOutput.err();
        }
        cc.jumpkick.engine.protocol.PluginCommandReport report;
        try {
            report = cc.jumpkick.cli.engine.EngineClient.pluginCommand(
                    cc.jumpkick.engine.EnginePaths.current(), projectDir, cache, command, appArgs);
        } catch (Exception e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Run", e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!report.found()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Run",
                    "the packaging plugin declares deploy command `" + command + "` but does not" + " register it"));
            return Exit.SOFTWARE;
        }
        if (report.error() != null) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Run", report.error()));
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
            cachedPlan = cc.jumpkick.cli.engine.EngineClient.execPlan(
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
     * {@link cc.jumpkick.engine.protocol.ExecPlan#mainIssue}.
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
     * sentence {@link cc.jumpkick.cli.tui.JkWedge#failedTo} renders after the red chip.
     */
    private static String mainIssueSentence(String issue, String coord) {
        Theme t = Theme.active();
        String main = Theme.colorize("main", t.highlight());
        String head = Theme.colorize("Failed", t.error()) + " to run " + Coord.module(coord) + ". ";
        return head
                + ("ambiguous".equals(issue)
                        ? "Multiple " + main + " methods found."
                        : "No valid " + main + " method was specified or detected");
    }

    /**
     * Tail of the settled Run CommandWedge: {@code Executing [yellow]`java -cp … Main`[/]} or
     * {@code Executing [yellow]`java -jar path`[/]} (from {@link
     * cc.jumpkick.engine.protocol.ExecPlan#display}), or a native binary path in the same shape.
     */
    private static String execTail(Path projectDir, cc.jumpkick.engine.protocol.ExecPlan plan) {
        Theme t = Theme.active();
        String command;
        if (plan.argv().size() == 1) {
            // Native binary — exec'd directly, no JVM.
            Path bin = Path.of(plan.argv().get(0));
            command = PathDisplay.of(bin, projectDir);
        } else if (!plan.display().isEmpty()) {
            // Engine display is already abbreviated: "java -cp … Main" or "java -jar rel/path".
            command = plan.display();
        } else {
            // Deploy / odd plans without a display string — join argv as a last resort.
            command = String.join(" ", plan.argv());
        }
        return "Executing " + Theme.colorize("`" + command + "`", t.shell());
    }

    /**
     * Prints the play {@link CommandWedge} to stderr (verbose/JSON modes, where no plan chip is
     * rendered). Same shape as the chip-mode settle: {@code ▶ Run Executing `java …`}.
     */
    private static void printExecBanner(Path projectDir, cc.jumpkick.engine.protocol.ExecPlan plan) {
        CliOutput.err(CommandWedge.working("Run", execTail(projectDir, plan)));
        CliOutput.err();
        // Reset any lingering SGR state so the program's own output starts from
        // the terminal's default colors (only when we're emitting color at all).
        if (Theme.colorEnabled()) {
            CliOutput.errRaw(Ansi.RESET);
            CliOutput.stderr().flush();
        }
    }

    /**
     * Workspace pre-build with the same live chrome as {@code jk build} at a root (JK-1201):
     * engine-tracked aggregate bar, per-module step chips, buffered module output, completion
     * lines. Settles the region itself on failure/cancel and returns {@code null}; on success the
     * region settles to an exec-style chip so the run banner follows cleanly.
     */
    private cc.jumpkick.runtime.WorkspaceResult runWorkspaceLive(cc.jumpkick.runtime.WorkspaceRequest request) {
        var view = cc.jumpkick.cli.tui.JkManager.plan(CliOutput.stdout(), "Run", true);
        var agg = new cc.jumpkick.cli.run.AggregateContext(view);
        java.util.Map<Path, List<String>> buffers = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> deferredOutput = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        int[] total = {0};
        var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public void onPreflight(String stage, int done, int totalUnits, String label) {
                agg.preflight(stage, done, totalUnits, label);
            }

            @Override
            public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                agg.applySnapshot(snap);
            }

            @Override
            public void onEtaEstimate(long millis) {
                view.setRemainingWorkEstimate(millis);
            }

            @Override
            public void onPlan(List<cc.jumpkick.runtime.ModulePlan> plan) {
                total[0] = plan.size();
            }

            @Override
            public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                var log = cc.jumpkick.cli.run.EventLogListener.open(
                        m.cache(), m.plan().name());
                List<String> buf = java.util.Collections.synchronizedList(new ArrayList<String>());
                buffers.put(m.dir(), buf);
                var lis = new cc.jumpkick.cli.run.AggregateModuleListener(
                        agg, m.coord(), m.plan().steps(), m.weight());
                lis.bufferOutputInto(buf);
                return cc.jumpkick.cli.run.CompositeBuildPlanListener.of(lis, log);
            }

            @Override
            public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                String completion = BuildCommand.completionLine(
                        o.success(), completed.incrementAndGet(), total[0], o.coord(), o.millis());
                if (view.animating()) {
                    view.addCompletion(completion);
                    synchronized (buf) {
                        if (!buf.isEmpty()) deferredOutput.addAll(buf);
                    }
                } else {
                    StringBuilder block = new StringBuilder();
                    synchronized (buf) {
                        for (String l : buf) block.append(l).append('\n');
                    }
                    block.append(completion);
                    view.writeAbove(block.toString());
                }
            }
        };
        cc.jumpkick.runtime.WorkspaceResult wr;
        try {
            wr = cc.jumpkick.cli.engine.EngineClient.buildWorkspace(
                    cc.jumpkick.engine.EnginePaths.current(), request, listener);
        } catch (cc.jumpkick.cli.engine.JobCancelledException e) {
            view.finishBuildPlanCancelled(deferredOutput);
            return null;
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), deferredOutput);
            return null;
        }
        if (!wr.success()) {
            String tail = wr.errors().isEmpty()
                    ? "workspace build failed"
                    : wr.errors().get(0);
            view.finishBuildPlanFailure(tail, deferredOutput);
            return null;
        }
        int n = Math.max(total[0], wr.modules().size());
        view.finishBuildPlanExec(n + (n == 1 ? " module ready" : " modules ready"), deferredOutput);
        return wr;
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }
}
