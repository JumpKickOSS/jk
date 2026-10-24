// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.watch.ProcessTrees;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk run} on a project: the build (tests skipped), then the program — the most
 * self-contained artifact, <strong>native &gt; assembly jar &gt; plain jar</strong>, as the
 * engine's exec plan names it, with inherited stdio. A workspace root builds the graph, a member
 * builds as {@code -m <member>}. {@code jk run}'s other targets (tools, scripts, coordinates) stay
 * with {@link cc.jumpkick.command.toolchain.ToolRunCommand}, which hands a project here.
 */
public final class RunVerb implements BuildVerb {

    private static final Words WORDS = new Words("Run", "building", "build", "Running");

    private final List<String> appArgs;

    /** {@code --debug-jvm}: the app JVM starts with a JDWP listener at this address; null for a plain run. */
    private @Nullable DebugJvm debugJvm;

    private Path cache = Path.of("");

    private RunVerb(List<String> appArgs, @Nullable DebugJvm debugJvm) {
        this.appArgs = List.copyOf(appArgs);
        this.debugJvm = debugJvm;
    }

    /**
     * {@code jk run} on the project at {@code dir}: {@code outer}'s global and build options, tests
     * skipped, then the program with {@code appArgs}.
     */
    public static int runProject(Invocation outer, Path dir, List<String> appArgs, @Nullable DebugJvm debugJvm) {
        return BuildVerbCommand.run(
                new RunVerb(appArgs, debugJvm),
                BuildVerbCommand.forward(outer, WorkspaceTarget.Tests.NOT_RUN, b -> {
                    b.putValue("dir", dir.toString());
                    b.flag("skip-tests", true);
                }),
                true);
    }

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "Build this project, then run it";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.RUN;
    }

    @Override
    public int before(BuildRun run) {
        this.cache = run.flags().cache();
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        return EngineClient.buildWorkspace(
                EnginePaths.current(),
                run.workspaceRequest(entryDir, modules, run.flags().jobs()),
                listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> {
                    if (run.standalone()) {
                        try {
                            return execTail(
                                    run.global().workingDir(),
                                    execPlan(run.global().workingDir()));
                        } catch (IOException e) {
                            return "Executing";
                        }
                    }
                    int n = Math.max(planned, r.modules().size());
                    return n + (n == 1 ? " module ready" : " modules ready");
                },
                r -> r.errors().isEmpty()
                        ? "workspace build failed"
                        : r.errors().getFirst(),
                true,
                r -> {
                    if (!run.standalone()) return null;
                    Path projectDir = run.global().workingDir();
                    try {
                        execPlan(projectDir);
                        return null;
                    } catch (EntryPointUnresolvedException e) {
                        String coord = ProjectInfos.buildTarget(ManifestPaths.manifestIn(projectDir), projectDir);
                        return mainIssueSentence(e.issue(), coord);
                    } catch (IOException e) {
                        return null;
                    }
                });
    }

    /**
     * The program, once the build is green: the engine's exec plan computed against the just-built
     * outputs. A device artifact (an APK) never forks on the host — the plan names the plugin's
     * deploy command instead.
     */
    @Override
    public int after(BuildRun run, int code) {
        if (code != 0) return code;
        Path projectDir = run.global().workingDir();
        BuildPlanConsole.Mode mode = run.mode();
        List<String> command;
        ExecPlan launched;
        try {
            ExecPlan plan = execPlan(projectDir);
            launched = plan;
            if (!plan.deployCommand().isEmpty()) {
                if (debugJvm != null) {
                    CommandWedge.printFail(
                            "Run", "--debug-jvm needs a JVM to attach to; this project runs through a deploy command");
                    return Exit.USAGE;
                }
                return dispatchDeployCommand(projectDir, cache, plan.deployCommand(), appArgs, mode);
            }
            command = new ArrayList<>(plan.argv());
            if (debugJvm != null && !command.contains(debugJvm.agentArg())) {
                CommandWedge.printFail("Run", "--debug-jvm needs a JVM to attach to; this run is not a java launch");
                return Exit.USAGE;
            }
        } catch (EntryPointUnresolvedException e) {
            String coord = ProjectInfos.buildTarget(ManifestPaths.manifestIn(projectDir), projectDir);
            CommandWedge.printLine(
                    JkWedge.failureLineCustom("Run", GlobalConfig.nerdFont(), mainIssueSentence(e.issue(), coord)));
            return Exit.DATA_ERR;
        } catch (IOException e) {
            CommandWedge.printFail("Run", e.getMessage());
            return Exit.USAGE;
        }
        if (debugJvm != null) DebugAttach.announce(debugJvm);
        try {
            if (mode == BuildPlanConsole.Mode.VERBOSE || mode == BuildPlanConsole.Mode.JSON) {
                // No chip was printed in these modes — show the banner line.
                printExecBanner(projectDir, execPlan(projectDir));
            } else {
                // The chip settled with the exec line; emit the blank separator + color reset.
                CliOutput.err();
                if (Theme.colorEnabled()) {
                    CliOutput.errRaw(Ansi.RESET);
                    CliOutput.stderr().flush();
                }
            }
            command.addAll(appArgs);
            // The program's own stdout is the last thing on this terminal — `jk run > app.out` must
            // not collect jk's closing blank.
            Process p = CliOutput.handOffTerminal(processFor(command, launched));
            // Skip the gap only once the exec actually started — a failed start() still owns the
            // terminal, and its error wedge has earned the envelope's trailing blank.
            CliOutput.skipTrailingBlank();
            return ProcessTrees.awaitExit(p);
        } catch (IOException e) {
            CommandWedge.printFail("Run", e.getMessage());
            return Exit.SOFTWARE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Exit.INTERRUPTED;
        }
    }

    /**
     * {@code command} as the plan launches it: the module's {@code .env} beneath this environment, and
     * a node server in its directory with the plan's environment over it.
     */
    private static ProcessBuilder processFor(List<String> command, ExecPlan plan) {
        ProcessBuilder builder = new ProcessBuilder(command);
        EnvLookup.layerUnder(builder.environment(), plan.appDotEnv());
        if (!plan.appEnv().isEmpty()) {
            builder.environment().putAll(plan.appEnv());
            builder.directory(Path.of(plan.workingDir()).toFile());
        }
        return builder;
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
        PluginCommandReport report;
        try {
            report = EngineClient.pluginCommand(EnginePaths.current(), projectDir, cache, command, appArgs);
        } catch (Exception e) {
            CommandWedge.printFail("Run", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!report.found()) {
            CommandWedge.printFail(
                    "Run",
                    "the packaging plugin declares deploy command `" + command + "` but does not" + " register it");
            return Exit.SOFTWARE;
        }
        if (report.error() != null) {
            CommandWedge.printFail("Run", report.error());
            return 1;
        }
        for (String line : report.output()) CliOutput.out(line);
        return report.exit();
    }

    /**
     * Engine-computed execution plan (artifact preference, RUN classpath, main-class). Memoized so
     * the console's exec-tail and the real exec agree.
     */
    private ExecPlan execPlan(Path projectDir) throws IOException {
        if (cachedPlan == null) {
            // The port is settled here, as late as the plan allows, so the free port a `0` asked
            // for is released as close to the JVM's own bind as this side can manage.
            if (debugJvm != null) debugJvm = DebugAttach.bind(debugJvm);
            cachedPlan = EngineClient.execPlan(EnginePaths.current(), projectDir, cache, "run", debugJvm);
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

    private @Nullable ExecPlan cachedPlan;

    /**
     * The build succeeded but the engine's main-class scan couldn't name an entry point — {@code
     * issue} is {@code "missing"} (nothing found) or {@code "ambiguous"} (several found), per
     * {@link cc.jumpkick.wire.protocol.ExecPlan#mainIssue}.
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
     * cc.jumpkick.wire.protocol.ExecPlan#display}), or a native binary path in the same shape.
     */
    private static String execTail(Path projectDir, ExecPlan plan) {
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
    private static void printExecBanner(Path projectDir, ExecPlan plan) {
        CommandWedge.printWorking("Run", execTail(projectDir, plan));
        CliOutput.err();
        // Reset any lingering SGR state so the program's own output starts from
        // the terminal's default colors (only when we're emitting color at all).
        if (Theme.colorEnabled()) {
            CliOutput.errRaw(Ansi.RESET);
            CliOutput.stderr().flush();
        }
    }
}
