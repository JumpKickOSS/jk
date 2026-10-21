// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.JobCancelledException;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.tui.BuildNotify;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One invocation of a build verb: its parsed flags, its transcript, and the helpers every verb
 * settles through, so a standalone project and a workspace end on the same exit codes.
 */
final class BuildRun {

    /**
     * What {@code -m} / {@code --affected} / {@code --affected-since} resolved to. {@code dirs} is
     * every module the run touches (empty with {@code empty}, or when nothing was selected).
     */
    record Selection(
            @Nullable String error, boolean empty, List<String> tokens, List<String> names, List<String> dirs) {

        static final Selection NONE = new Selection(null, false, List.of(), List.of(), List.of());

        /** True when a selector was given and resolved: the run is confined to {@link #dirs}. */
        boolean confines() {
            return error == null && !tokens.isEmpty();
        }
    }

    /** One engine call for a standalone project, given the listener factory to stream into. */
    interface SingleCall {
        BuildPlanResult run(Function<List<Task>, BuildPlanListener> listeners) throws IOException;
    }

    private final BuildVerb verb;
    private final Invocation invocation;
    private final @Nullable CliSessionTranscript session;
    private final String variant;
    private final Map<String, String> clientEnv;
    private BuildFlags flags;

    BuildRun(
            BuildVerb verb,
            BuildFlags flags,
            Invocation invocation,
            @Nullable CliSessionTranscript session,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv) {
        this.verb = verb;
        this.flags = flags;
        this.invocation = invocation;
        this.session = session;
        this.variant = variant == null ? "" : variant;
        this.clientEnv = clientEnv == null ? Map.of() : clientEnv;
    }

    BuildFlags flags() {
        return flags;
    }

    void flags(BuildFlags narrowed) {
        this.flags = narrowed;
    }

    GlobalOptions global() {
        return flags.global();
    }

    Invocation invocation() {
        return invocation;
    }

    @Nullable
    CliSessionTranscript session() {
        return session;
    }

    /** The compact variant selector the request carries; empty for the default variant. */
    String variant() {
        return variant;
    }

    Map<String, String> clientEnv() {
        return clientEnv;
    }

    String name() {
        return verb.words().name();
    }

    BuildPlanConsole.Mode mode() {
        return BuildPlanConsole.modeFor(global());
    }

    /** Resolve the module selectors against the project at {@code entryDir}. */
    Selection select(Path entryDir) {
        List<String> tokens = ModuleSelectors.tokens(flags.modulesSpec(), flags.affectedSince(), flags.affectedWip());
        if (tokens.isEmpty()) return Selection.NONE;
        ProjectInfo info =
                ProjectInfos.orError(entryDir, flags.modulesSpec(), flags.affectedSince(), flags.affectedWip());
        if (info == null) {
            return new Selection(
                    "cannot load project summary for module selection", false, List.of(), List.of(), List.of());
        }
        if (info.error() != null && !info.error().isBlank()) {
            return new Selection(info.error(), false, List.of(), List.of(), List.of());
        }
        List<String> names = ModuleScopeHint.namesFrom(info);
        if (info.moduleDirs().isEmpty()) return new Selection(null, true, tokens, names, List.of());
        return new Selection(null, false, tokens, names, info.moduleDirs());
    }

    /** What a selection that matched nothing says. */
    String nothingSelected() {
        String modules = flags.modulesSpec();
        String since = flags.affectedSince();
        boolean byModules = modules != null && !modules.isBlank();
        boolean bySince = since != null && !since.isBlank();
        if (byModules && bySince) return "nothing matched --modules=" + modules + " ∩ --affected-since=" + since;
        if (byModules) return "nothing matched --modules=" + modules;
        if (bySince) return "nothing affected since " + since;
        return "nothing selected to " + verb.words().infinitive();
    }

    void ok(String message) {
        CommandWedge.printOk(name(), message);
        if (session != null) session.wedge(message);
    }

    void fail(@Nullable String message) {
        CommandWedge.printFail(name(), message);
        if (session != null) session.error(message);
    }

    /**
     * Run one standalone project through {@code call} under {@code spec} and settle it: the
     * transcript, the wedge, and the exit code every build verb reports — 0, 1 for a cancel or a
     * failure, {@link Exit#CONFIG} for a refusal, {@link Exit#TESTS_FAILED} when tests failed.
     */
    int settleSingle(ConsoleSpec spec, String module, TestSummary @Nullable [] tests, SingleCall call) {
        BuildPlanConsole.Mode mode = mode();
        BuildPlanResult result;
        try {
            result = call.run(steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, module));
        } catch (JobCancelledException e) {
            CommandWedge.printLine(JkWedge.cancelledJobLine(name(), GlobalConfig.nerdFont(), false, ""));
            if (session != null) session.wedge(name() + " job was cancelled");
            return 1;
        } catch (EngineWireException e) {
            fail(e.getMessage());
            return Exit.CONFIG;
        } catch (IOException e) {
            CommandWedge.printFail(name(), e.getMessage());
            if (session != null) session.error(Errors.text(e));
            return Exit.SOFTWARE;
        }
        TestSummary summary = tests == null ? null : tests[0];
        if (session != null) {
            session.module(module).absorb(result);
            String wedge = result.success()
                    ? spec.onSuccess().apply(result)
                    : result.cancelled()
                            ? name() + " job was cancelled"
                            : spec.onFailure().apply(result);
            if (wedge != null) session.wedge(wedge);
        }
        return exitOf(result, summary);
    }

    /** The exit code of a finished standalone run. */
    static int exitOf(BuildPlanResult result, @Nullable TestSummary tests) {
        if (result.success()) return 0;
        if (result.cancelled()) return 1;
        for (var d : result.errors()) {
            if ("affected-refuse".equals(d.code())) return Exit.CONFIG;
        }
        if (tests != null && !tests.allPassed()) return Exit.TESTS_FAILED;
        return 1;
    }

    /**
     * The workspace request every build verb sends: the run's flags over {@code modules}, the
     * engine forecasting the dirty set and freshening a stale workspace lock itself. {@code
     * concurrency} is the module budget.
     */
    WorkspaceRequest workspaceRequest(Path entryDir, List<String> modules, boolean skipTests, int concurrency) {
        return new WorkspaceRequest(
                        entryDir,
                        flags.cache(),
                        flags.jdksDir(),
                        flags.workers(),
                        flags.profile(),
                        skipTests,
                        global().verbose,
                        concurrency,
                        null,
                        true,
                        true)
                .withVariant(variant, clientEnv)
                .withKeepGoing(flags.keepGoing())
                .withModules(modules);
    }

    /** OSC desktop notify when estimate/elapsed ≥ 1m, or {@code --notify} forces it. */
    void notify(BuildNotify.Outcome outcome, Path entryDir, long estimateMs, long elapsedMs) {
        BuildNotify.maybeNotify(CliOutput.stdout(), global(), outcome, projectGaLabel(entryDir), estimateMs, elapsedMs);
    }

    /** {@code group:name:version} for the OSC window title. */
    static String projectGavLabel(Path entryDir) {
        ProjectInfo info = ProjectInfos.orNull(entryDir);
        if (info == null) return "project";
        return label(info.group()) + ":" + label(info.name()) + ":" + label(info.version());
    }

    /** {@code group:name} for the plan header and desktop notifications. */
    static String projectGaLabel(Path entryDir) {
        ProjectInfo info = ProjectInfos.orNull(entryDir);
        if (info == null) return "project";
        return label(info.group()) + ":" + label(info.name());
    }

    private static String label(String value) {
        return value.isBlank() ? "?" : value;
    }
}
