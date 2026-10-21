// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.JobCancelledException;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.tui.BuildNotify;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.command.CwdModuleScope;
import cc.jumpkick.command.VariantSelection;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Every build command, as one command: the shared options, then validation, selection, the JDK
 * preflight and one run — a workspace through the orchestrator, a standalone project through one
 * plan — settled the same way whatever the verb. A {@link BuildVerb} supplies what differs.
 */
public final class BuildVerbCommand implements CliCommand {

    private final Supplier<BuildVerb> verbs;
    private final BuildVerb shape;

    private BuildVerbCommand(Supplier<BuildVerb> verbs) {
        this.verbs = verbs;
        this.shape = verbs.get();
        requireNoSharedOptions(shape.name(), shape.affected(), shape.ownOptions());
    }

    /** {@code jk compile}. */
    public static BuildVerbCommand compile() {
        return new BuildVerbCommand(CompileVerb::new);
    }

    /** {@code jk test}. */
    public static BuildVerbCommand test() {
        return new BuildVerbCommand(TestVerb::new);
    }

    /** {@code jk build}. */
    public static BuildVerbCommand build() {
        return new BuildVerbCommand(PackageVerb::new);
    }

    /**
     * A verb's own options never repeat a shared one: a second declaration is a second help string
     * and a second parse, which is how the build commands drifted apart.
     */
    static void requireNoSharedOptions(String command, BuildFlags.Affected affected, List<Opt> own) {
        Set<String> shared = new HashSet<>();
        for (Opt opt : BuildFlags.options(BuildFlags.Reach.DELIVER, affected)) shared.addAll(opt.names());
        for (Opt opt : own) {
            for (String name : opt.names()) {
                if (shared.contains(name)) {
                    throw new IllegalStateException(
                            "jk " + command + " declares the shared build option " + name + " itself");
                }
            }
        }
    }

    @Override
    public String name() {
        return shape.name();
    }

    @Override
    public String description() {
        return shape.description();
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(BuildFlags.options(shape.reach(), shape.affected()));
        opts.addAll(shape.ownOptions());
        return opts;
    }

    @Override
    public int run(Invocation in) {
        return run(verbs.get(), in, true);
    }

    /**
     * Run {@code verb} for {@code in}. {@code transcript} is false for a run embedded in a loop
     * that keeps its own (the dev loop), so each recompile does not open a session of its own.
     */
    static int run(BuildVerb verb, Invocation in, boolean transcript) {
        String name = verb.words().name();
        BuildFlags flags;
        try {
            flags = BuildFlags.from(in);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail(name, e.getMessage());
            return Exit.CONFIG;
        }
        BuildFlags.warnGateOverride(in, flags.global());
        flags.installSession();
        Path dir = flags.global().workingDir();
        if (ProjectContext.require(dir, name).isEmpty()) return Exit.CONFIG;
        String variant = VariantSelection.install(in, dir);
        CliSessionTranscript session = transcript ? CliSessionTranscript.open(dir, verb.name(), argv(verb, in)) : null;
        if (session != null) session.announceIf(flags.global().verbose);
        BuildRun run = new BuildRun(
                verb, flags, in, session, variant, SessionContext.current().clientEnv());
        try {
            verb.parse(in, run);
        } catch (IllegalArgumentException e) {
            run.fail(e.getMessage());
            return finish(run, Exit.CONFIG);
        }
        return finish(run, run(verb, run, dir));
    }

    private static int finish(BuildRun run, int code) {
        return CliSessionTranscript.finish(run.session(), code, run.global().verbose);
    }

    private static int run(BuildVerb verb, BuildRun run, Path dir) {
        // Workspace root: whole graph. Workspace member: the same as `-m <this-module>`.
        ProjectInfo info = ProjectInfos.orNull(dir);
        CwdModuleScope.Resolved scope = CwdModuleScope.resolve(dir, run.flags().modulesSpec(), info);
        if (scope.inferredFromCwd()) run.flags(run.flags().withModulesSpec(scope.modulesSpec()));
        int early = verb.before(run);
        if (early >= 0) return early;
        // A pinned JDK that is not installed downloads here, with the `jk jdk install` bar, before any
        // build console opens — never as a silent step inside the engine.
        if (!JdkPreflight.ensure(dir, info, run.flags().jdksDir(), run.mode())) return Exit.FAILURE;
        if (info != null && info.workspaceRoot()) return workspace(verb, run, dir, info);
        if (scope.workspaceMember()) return workspace(verb, run, scope.workspaceRoot(), null);
        return single(verb, run, dir);
    }

    // ---- standalone project ------------------------------------------------------------------

    private static int single(BuildVerb verb, BuildRun run, Path dir) {
        if (!ManifestPaths.describesProject(dir)) {
            run.fail("no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        // -m / --affected still validate on a lone project: `-m bogus` must not silently build, and
        // a selection that leaves this project out is a no-op.
        if (run.flags().selects()) {
            BuildRun.Selection sel = run.select(dir);
            if (sel.error() != null) {
                run.fail(sel.error());
                return Exit.CONFIG;
            }
            Path here = dir.toAbsolutePath().normalize();
            boolean hit = sel.dirs().stream()
                    .anyMatch(d -> Path.of(d).toAbsolutePath().normalize().equals(here));
            if (sel.empty() || !hit) {
                run.ok(run.nothingSelected());
                return 0;
            }
        }
        try {
            dir = dir.toRealPath();
        } catch (IOException ignored) {
            // the lexical path is the next best key
        }
        int code;
        try {
            code = verb.single(run, dir);
        } catch (IOException e) {
            run.fail(Errors.text(e));
            return Exit.SOFTWARE;
        }
        return verb.afterSingle(run, dir, code);
    }

    // ---- workspace ---------------------------------------------------------------------------

    private static int workspace(BuildVerb verb, BuildRun run, Path entryDir, @Nullable ProjectInfo rootInfo) {
        BuildRun.Selection sel = run.select(entryDir);
        if (sel.error() != null) {
            run.fail(sel.error());
            return Exit.CONFIG;
        }
        if (sel.empty()) {
            run.ok(run.nothingSelected());
            return 0;
        }
        if (!sel.confines() && rootInfo != null && rootInfo.moduleDirs().isEmpty()) {
            run.ok("workspace declares no modules");
            return 0;
        }
        if (sel.confines()) verb.selected(run, sel.dirs());
        int prepared = verb.prepareWorkspace(run, entryDir, sel);
        if (prepared >= 0) return prepared;
        BuildPlanConsole.Mode mode = run.mode();
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;
        ModuleScopeHint.print(verb.words().gerund(), sel.names(), run.global().outputIsJson());
        return live
                ? workspaceLive(verb, run, entryDir, sel.tokens(), mode)
                : workspaceHeadless(verb, run, entryDir, sel.tokens());
    }

    /**
     * The live region: one {@link JkManager} with a header, one bar over the whole graph and the
     * modules building right now, opened before the engine is asked so preflight is never silent.
     */
    private static int workspaceLive(
            BuildVerb verb, BuildRun run, Path entryDir, List<String> modules, BuildPlanConsole.Mode mode) {
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        EnginePrewarm.ensure();
        long start = Clock.SYSTEM.nanos();
        JkManager view = JkManager.plan(CliOutput.stdout(), run.name(), animate);
        view.setPlanCoord(BuildRun.projectGaLabel(entryDir));
        view.setWindowTitle("JumpKick - " + verb.words().title() + " " + BuildRun.projectGavLabel(entryDir) + "...");
        AggregateContext agg = new AggregateContext(view);
        var runView =
                new WorkspaceRunView(new WorkspaceRunView.Chrome(run.name(), true), entryDir, run.session(), false);
        WorkspaceResult result;
        try {
            result = verb.workspace(run, entryDir, modules, runView.live(view, agg));
        } catch (JobCancelledException e) {
            view.finishBuildPlanCancelled(List.of());
            if (run.session() != null) run.session().wedge(run.name() + " job was cancelled");
            run.notify(BuildNotify.Outcome.CANCELLED, entryDir, view.etaEstimateMs(), BuildTails.elapsedMsSince(start));
            return 1;
        } catch (IOException e) {
            runView.finishEvent(false, BuildTails.elapsedMsSince(start));
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), List.of());
            if (run.session() != null) run.session().error(String.valueOf(e.getMessage()));
            run.notify(BuildNotify.Outcome.FAILED, entryDir, view.etaEstimateMs(), BuildTails.elapsedMsSince(start));
            return e instanceof EngineWireException ? Exit.CONFIG : Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        long estimate = view.etaEstimateMs();
        return runView.settleLive(
                view,
                agg,
                result,
                elapsed,
                verb.tails(run, runView, start),
                settled -> run.notify(outcome(settled), entryDir, estimate, elapsed));
    }

    /** No region ({@code -O jsonl}, {@code --verbose}, a pipe): the same events and the same settle. */
    private static int workspaceHeadless(BuildVerb verb, BuildRun run, Path entryDir, List<String> modules) {
        boolean json = run.global().outputIsJson();
        var runView =
                new WorkspaceRunView(new WorkspaceRunView.Chrome(run.name(), true), entryDir, run.session(), json);
        long start = Clock.SYSTEM.nanos();
        WorkspaceResult result;
        try {
            result = verb.workspace(run, entryDir, modules, runView.headless());
        } catch (JobCancelledException e) {
            int code = runView.settleHeadlessCancelled(BuildTails.elapsedMsSince(start));
            run.notify(BuildNotify.Outcome.CANCELLED, entryDir, 0, BuildTails.elapsedMsSince(start));
            return code;
        } catch (IOException e) {
            runView.finishEvent(false, BuildTails.elapsedMsSince(start));
            if (!json) CommandWedge.printFail(run.name(), e.getMessage());
            if (run.session() != null) run.session().error(Errors.text(e));
            run.notify(BuildNotify.Outcome.FAILED, entryDir, 0, BuildTails.elapsedMsSince(start));
            return e instanceof EngineWireException ? Exit.CONFIG : Exit.SOFTWARE;
        }
        long elapsed = BuildTails.elapsedMsSince(start);
        return runView.settleHeadless(
                result,
                elapsed,
                verb.tails(run, runView, start),
                settled -> run.notify(outcome(settled), entryDir, 0, elapsed));
    }

    private static BuildNotify.Outcome outcome(WorkspaceRunView.Settled settled) {
        return switch (settled) {
            case CANCELLED -> BuildNotify.Outcome.CANCELLED;
            case SUCCEEDED -> BuildNotify.Outcome.COMPLETE;
            case GRAPH_ERRORS, FAILED -> BuildNotify.Outcome.FAILED;
        };
    }

    /**
     * The invocation of a build verb another command runs over {@code dir}: {@code outer}'s global
     * and variant options, the cache, then whatever {@code extra} sets.
     */
    static Invocation embedded(Invocation outer, Path dir, Path cache, Consumer<Invocation.Builder> extra) {
        Invocation.Builder b = Invocation.builder();
        for (Opt opt : GlobalOptions.globalOpts()) copy(outer, opt, b);
        for (Opt opt : VariantSelection.options()) copy(outer, opt, b);
        b.putValue("dir", dir.toString());
        b.putValue("cache-dir", cache.toString());
        extra.accept(b);
        return b.build();
    }

    /** Copy {@code opt}'s value(s) or flag from {@code in} onto {@code b}. */
    static void copy(Invocation in, Opt opt, Invocation.Builder b) {
        String name = opt.canonicalName();
        if (opt.takesValue()) {
            for (String v : in.values(name)) b.addValue(name, v);
        } else if (in.isSet(name)) {
            b.flag(name, true);
        }
    }

    /** Compact argv for the transcript: the command and the flags that say what it selected. */
    static List<String> argv(BuildVerb verb, Invocation in) {
        List<String> argv = new ArrayList<>();
        argv.add(verb.name());
        if (in.isSet("skip-tests")) argv.add("--skip-tests");
        in.value("profile").ifPresent(p -> {
            argv.add("-p");
            argv.add(p);
        });
        String modules = CommonOpts.modulesSpec(in);
        if (modules != null) {
            argv.add("-m");
            argv.add(modules);
        }
        in.value("affected-since").ifPresent(r -> {
            argv.add("--affected-since");
            argv.add(r);
        });
        if (in.isSet("affected")) argv.add("--affected");
        if (in.isSet("serial-tests") || in.isSet("no-parallel-tests")) argv.add("--serial-tests");
        in.value("workers").ifPresent(w -> {
            argv.add("-w");
            argv.add(w);
        });
        for (String s : in.values("suite")) {
            argv.add("-s");
            argv.add(s);
        }
        for (String c : in.values("class")) argv.add("--class=" + c);
        if (in.isSet("exclusive")) argv.add("-X");
        if (in.isSet("aot-cache")) argv.add("--aot-cache");
        return argv;
    }
}
