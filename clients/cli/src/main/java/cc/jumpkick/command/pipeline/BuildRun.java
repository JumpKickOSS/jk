// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.tui.BuildNotify;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One invocation of a build verb: its parsed flags, its transcript, and the helpers every verb
 * settles through. A standalone project runs as a workspace of one, so both end on the same exit
 * codes by the same path.
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

    private final BuildVerb verb;
    private final Invocation invocation;
    private final @Nullable CliSessionTranscript session;
    private final String variant;
    private final Map<String, String> clientEnv;
    private BuildFlags flags;
    private boolean standalone;

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

    /** True when the project is not part of a workspace: it runs as a workspace of one. */
    boolean standalone() {
        return standalone;
    }

    void standalone(boolean standalone) {
        this.standalone = standalone;
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
     * Whether this run skips tests: always for a goal that runs none, otherwise on {@code
     * --skip-tests} or {@code --scripts-only}. The one place a verb's test policy is applied.
     */
    boolean skipTests() {
        return verb.target().tests() == WorkspaceTarget.Tests.NOT_RUN || flags.skipTests();
    }

    /**
     * The request every build verb sends: the run's flags over {@code modules}, the engine
     * forecasting the dirty set and freshening a stale workspace lock itself. {@code concurrency}
     * is the module budget.
     */
    WorkspaceRequest workspaceRequest(Path entryDir, List<String> modules, int concurrency) {
        return new WorkspaceRequest(
                        entryDir,
                        flags.cache(),
                        flags.jdksDir(),
                        flags.workers(),
                        flags.profile(),
                        skipTests(),
                        global().verbose,
                        concurrency,
                        null,
                        true,
                        true)
                .withVariant(variant, clientEnv)
                .withKeepGoing(flags.keepGoing())
                .withModules(modules);
    }

    /** {@link #workspaceRequest} for the verb's goal, as {@code spec} names it, at the run's module budget. */
    WorkspaceRequest request(Path entryDir, List<String> modules, WorkspaceSpec spec) {
        return workspaceRequest(entryDir, modules, flags.jobs()).withSpec(spec);
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
