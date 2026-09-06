// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import cc.jumpkick.wire.protocol.CatalogReadAck;
import cc.jumpkick.wire.protocol.DenyReport;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.GeneratedFiles;
import cc.jumpkick.wire.protocol.GuardFreezeAck;
import cc.jumpkick.wire.protocol.IdeWireModel;
import cc.jumpkick.wire.protocol.ModuleGraphAck;
import cc.jumpkick.wire.protocol.NewProjectAck;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.protocol.WhyReport;
import cc.jumpkick.wire.runtime.BuildForecast;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.HostedEvents;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * CLI-side counterpart to the engine's {@code EngineServer}: ensures a live version-matched engine
 * and exposes the hosted verbs. Probes live in {@link EngineProbe}, spawn/takeover/AOT in {@link
 * EngineSpawn}, request records in {@link EngineRequests}, fat hosted bodies in {@link
 * EngineHosted}. This type stays the one command-facing facade for verbs so adding {@code jk quux}
 * does not scatter imports across six collaborators.
 */
public final class EngineClient {

    private EngineClient() {}

    /**
     * The one entry point real commands use: a live, version-matched engine is guaranteed to be
     * reachable at {@code paths.socket} when this returns normally. Spawns lazily if none is
     * running; kills and replaces a stale (version-mismatched) engine transparently. Throws with a
     * message pointing at the engine's log file if it still can't be reached after a fresh spawn
     * per {@code docs/architecture.md}, the engine is load-bearing and this is not silently swallowed.
     */
    public static EngineProbe.Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion)
            throws IOException {
        return EngineSpawn.ensure(paths, clientVersion);
    }

    /**
     * Run a workspace build against the engine at {@code paths} instead of in-process — the engine
     * equivalent of the engine's {@code BuildService.buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched engine first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/architecture.md} there is no in-process fallback.
     */
    public static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener) throws IOException {
        return EngineJobs.buildWorkspace(paths, req, listener);
    }

    /** Workspace-member {@code jk image} — workspace events, image terminal on the module. */
    public static WorkspaceResult runImageWorkspace(
            EnginePaths.Paths paths, EngineRequests.ImageRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineJobs.runImageWorkspace(paths, req, listener);
    }

    /** Workspace {@code jk compile} — workspace events, compile-only terminal on the selection. */
    public static WorkspaceResult runCompileWorkspace(
            EnginePaths.Paths paths, EngineRequests.CompileRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineJobs.runCompileWorkspace(paths, req, listener);
    }

    /**
     * Run a single project's test plan against the engine (Task 3) — see {@link
     * EngineJobs#runTest} for the exact contract.
     */
    public static BuildPlanResult runTest(
            EnginePaths.Paths paths,
            EngineRequests.TestRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut)
            throws IOException {
        return EngineJobs.runTest(paths, req, listenerFactory, testResultOut);
    }

    /**
     * Run a single (non-workspace) project's build against the engine — the engine equivalent of
     * {@code BuildCommand.runForDir}'s {@code agg == null} branch — see {@link
     * EngineJobs#runSingleBuild} for the exact contract.
     */
    public static BuildPlanResult runSingleBuild(
            EnginePaths.Paths paths,
            EngineRequests.SingleBuildRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary @Nullable [] testResultOut,
            String @Nullable [] buildOutcomeOut)
            throws IOException {
        return EngineJobs.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /** One engine-hosted jk.toml edit (EDIT_REQUEST): returns changed; throws on error. */
    public static boolean edit(EnginePaths.Paths paths, Path file, String op, List<String> args) throws IOException {
        return EngineReads.edit(paths, file, op, args);
    }

    public static String editDetail(EnginePaths.Paths paths, Path file, String op, List<String> args)
            throws IOException {
        return EngineReads.editDetail(paths, file, op, args);
    }

    public static CacheInventoryAck cacheInventory(
            EnginePaths.Paths paths,
            String query,
            Path cache,
            @Nullable Path store,
            List<String> terms,
            List<String> coords,
            boolean dryRun)
            throws IOException {
        return EngineReads.cacheInventory(paths, query, cache, store, terms, coords, dryRun);
    }

    /** Engine-hosted {@code jk new} / init scaffold. */
    public static NewProjectAck newProject(EnginePaths.Paths paths, EngineRequests.NewProjectRequest req)
            throws IOException {
        return EngineReads.newProject(paths, req);
    }

    /** Module DAG for {@code jk explain --graph}. */
    public static ModuleGraphAck moduleGraph(
            EnginePaths.Paths paths,
            Path dir,
            @Nullable String format,
            @Nullable String modules,
            @Nullable String affectedSince)
            throws IOException {
        return EngineReads.moduleGraph(paths, dir, format, modules, affectedSince);
    }

    /** Layered library catalog (list / search / wizard picker). */
    public static CatalogReadAck catalogRead(
            EnginePaths.Paths paths,
            Path dir,
            @Nullable Path cache,
            String query,
            List<String> terms,
            boolean offline,
            boolean includeCached,
            boolean bundledOnly)
            throws IOException {
        return EngineReads.catalogRead(paths, dir, cache, query, terms, offline, includeCached, bundledOnly);
    }

    /**
     * Project summary (PROJECT_INFO) — replaces client-side project-file peeks.
     * In-process twin under test/no-engine.
     */
    public static ProjectInfo projectInfo(EnginePaths.Paths paths, Path dir) throws IOException {
        return projectInfo(paths, dir, null, null);
    }

    public static ProjectInfo projectInfo(
            EnginePaths.Paths paths, Path dir, @Nullable String modules, @Nullable String affectedSince)
            throws IOException {
        return projectInfo(paths, dir, modules, affectedSince, false);
    }

    /** {@code counts=true} adds the source/test tree-walk counts — jk status only. */
    public static ProjectInfo projectInfo(
            EnginePaths.Paths paths, Path dir, @Nullable String modules, @Nullable String affectedSince, boolean counts)
            throws IOException {
        return projectInfo(paths, dir, modules, affectedSince, false, counts);
    }

    public static ProjectInfo projectInfo(
            EnginePaths.Paths paths,
            Path dir,
            @Nullable String modules,
            @Nullable String affectedSince,
            boolean affectedWip,
            boolean counts)
            throws IOException {
        return EngineReads.projectInfo(paths, dir, modules, affectedSince, affectedWip, counts);
    }

    /**
     * Thin-client deny check: the [deny] policy is user-authored jk.toml and therefore parses
     * engine-side only; one synchronous DENY_CHECK round trip returns the violations.
     */
    /** Thin-client IDE model: engine computes the workspace model, client generates the files. */
    public static IdeWireModel ideModel(EnginePaths.Paths paths, Path dir, Path cache, @Nullable Path jdksDir)
            throws IOException {
        return EngineReads.ideModel(paths, dir, cache, jdksDir);
    }

    /** A plugin-declared command, worker-executed engine-side (found=false → normal help). */
    public static PluginCommandReport pluginCommand(
            EnginePaths.Paths paths, Path dir, Path cache, String command, List<String> args) throws IOException {
        return EngineReads.pluginCommand(paths, dir, cache, command, args);
    }

    /** Thin-client generator run: engine renders content, client guards/writes/prints. */
    public static GeneratedFiles generate(EnginePaths.Paths paths, Path dir, String kind) throws IOException {
        return EngineReads.generate(paths, dir, kind, Map.of());
    }

    /** As above with generator parameters (scaffold inputs etc.). */
    public static GeneratedFiles generate(EnginePaths.Paths paths, Path dir, String kind, Map<String, String> params)
            throws IOException {
        return EngineReads.generate(paths, dir, kind, params);
    }

    /** Thin-client tree render: engine walks the graph, client substitutes its Theme into the tags. */
    public static String treeRender(
            EnginePaths.Paths paths, Path dir, int maxDepth, boolean flatten, boolean stack, List<String> scopes)
            throws IOException {
        return EngineReads.treeRender(paths, dir, maxDepth, flatten, stack, scopes);
    }

    /** Thin-client why lookup: lock matching + provenance paths, engine-side. */
    public static WhyReport why(EnginePaths.Paths paths, Path dir, String query) throws IOException {
        return EngineReads.why(paths, dir, query);
    }

    /** {@code jk guard freeze}: the engine grows (or retires) one rule's baseline; one sync round trip. */
    public static GuardFreezeAck guardFreeze(
            EnginePaths.Paths paths, Path dir, String ruleId, @Nullable String reason, boolean retire)
            throws IOException {
        return EngineReads.guardFreeze(paths, dir, ruleId, reason, retire);
    }

    public static DenyReport denyCheck(EnginePaths.Paths paths, Path dir) throws IOException {
        return EngineReads.denyCheck(paths, dir);
    }

    /**
     * Execution plan: engine decides run/dev argv, install layout, or aot-cache layout; caller
     * executes.
     */
    public static ExecPlan execPlan(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            @Nullable String mainOverride,
            @Nullable String binName)
            throws IOException {
        return EngineReads.execPlan(paths, dir, cache, kind, mainOverride, binName, null, null);
    }

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    public static ExecPlan execPlan(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            @Nullable String mainOverride,
            @Nullable String binName,
            @Nullable Path binDir,
            @Nullable Path libDir)
            throws IOException {
        return EngineReads.execPlan(paths, dir, cache, kind, mainOverride, binName, binDir, libDir);
    }

    /**
     * Pre-flight a build's dirty forecast against the engine — {@code jk build}'s fully-cached
     * shortcut and dirty hint (see {@link EngineReads#forecast}).
     */
    public static BuildForecast forecast(EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests)
            throws IOException {
        return EngineReads.forecast(paths, entryDir, cache, skipTests);
    }

    /**
     * Forecast a build against the engine ({@code jk explain}) — see {@link
     * EngineExplainDecoder#explain} for the exact contract. {@code etaOut} (may be
     * {@code null}) receives engine-computed estimates in millis, {@code 0} = unknown:
     * slot {@code [0]} the remaining-work ETA, and — when the array has a second slot — slot
     * {@code [1]} the full-rebuild ETA (the rebuild-effort denominator). Length-guarded, so a
     * one-slot caller still gets the plain ETA.
     */
    public static ExplainPlan explain(
            EnginePaths.Paths paths, EngineRequests.ExplainRequest req, long @Nullable [] etaOut) throws IOException {
        return EngineExplainDecoder.explain(paths, req, etaOut);
    }

    /**
     * Run {@code jk lock}'s workspace cascade against the engine — see {@link
     * EngineResolveAdapter#runLock} for the exact contract. {@code handler} is the command's
     * renderer; the returned outcome's {@code exitCode} is authoritative (computed engine-side).
     */
    public static EngineRequests.LockOutcome runLock(
            EnginePaths.Paths paths, EngineRequests.LockRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runLock(paths, req, handler);
    }

    /**
     * Run {@code jk update}'s full re-resolve cascade against the engine (rides {@code jk lock}'s
     * event vocabulary) — see {@link EngineResolveAdapter#runUpdate}.
     */
    public static EngineRequests.LockOutcome runUpdate(
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runUpdate(paths, req, handler);
    }

    /**
     * Run {@code jk update --git [<name>]} against the engine ({@code gitTarget == null} refreshes
     * every git dependency) — see {@link EngineResolveAdapter#runUpdateGitOnly}.
     */
    public static EngineRequests.LockOutcome runUpdateGitOnly(
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, @Nullable String gitTarget) throws IOException {
        return EngineResolveAdapter.runUpdateGitOnly(paths, req, gitTarget);
    }

    /**
     * Run {@code jk sync}'s single plan against the engine — see {@link
     * EngineResolveAdapter#runSync} for the exact contract (the {@code jk test} listener-factory
     * shape, plus fetched/up-to-date count holders for the summary line).
     */
    public static BuildPlanResult runSync(
            EnginePaths.Paths paths,
            EngineRequests.SyncRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        return EngineResolveAdapter.runSync(paths, req, listenerFactory, fetchedOut, upToDateOut);
    }

    /**
     * Report declared dependencies with newer versions available against the engine ({@code jk
     * outdated}) — one synchronous request, one {@link cc.jumpkick.wire.protocol.OutdatedReport}
     * back. Read-only: the engine enumerates versions and writes nothing.
     */
    public static OutdatedReport runOutdated(EnginePaths.Paths paths, EngineRequests.OutdatedRequest req)
            throws IOException {
        return EngineResolveAdapter.runOutdated(paths, req);
    }

    /** Ranked tests for the working tree or {@code since...HEAD} — no compile, no run. */
    public static AffectedTestsReport runAffectedTests(
            EnginePaths.Paths paths,
            Path dir,
            TestSelection selection,
            @Nullable String since,
            @Nullable String modules)
            throws IOException {
        return EngineResolveAdapter.runAffectedTests(paths, dir, selection, since, modules);
    }

    public static BuildPlanResult runAudit(
            EnginePaths.Paths paths,
            EngineRequests.AuditRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            HostedEvents.FindingObserver findings)
            throws IOException {
        return EngineHosted.runAudit(paths, req, listenerFactory, findings);
    }

    public static EngineRequests.FormatOutcome runFormat(
            EnginePaths.Paths paths,
            EngineRequests.FormatRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            HostedEvents.FileObserver files)
            throws IOException {
        return EngineHosted.runFormat(paths, req, listenerFactory, files);
    }

    public static EngineRequests.PublishOutcome runPublish(
            EnginePaths.Paths paths,
            EngineRequests.PublishRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runPublish(paths, req, listenerFactory);
    }

    public static BuildPlanResult runImage(
            EnginePaths.Paths paths,
            EngineRequests.ImageRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            EngineRequests.ImageSummary[] summaryOut)
            throws IOException {
        return EngineHosted.runImage(paths, req, listenerFactory, summaryOut);
    }

    public static EngineRequests.ImportOutcome runImport(
            EnginePaths.Paths paths,
            EngineRequests.ImportRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            HostedEvents.NoteObserver notes)
            throws IOException {
        return EngineHosted.runImport(paths, req, listenerFactory, notes);
    }

    public static HostedEvents.Provision provision(
            EnginePaths.Paths paths, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException {
        return EngineHosted.provision(paths, projectDir, toolsRoot, noDiscover, gradle);
    }

    /** Provision a named build tool at a named version, rather than the one a project asks for. */
    public static HostedEvents.Provision provisionTool(
            EnginePaths.Paths paths, String tool, String version, Path toolsRoot, boolean noDiscover)
            throws IOException {
        return EngineHosted.provisionTool(paths, tool, version, toolsRoot, noDiscover);
    }

    public static BuildPlanResult runCompile(
            EnginePaths.Paths paths,
            EngineRequests.CompileRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runCompile(paths, req, listenerFactory);
    }

    public static BuildPlanResult runTrain(
            EnginePaths.Paths paths,
            EngineRequests.TrainRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runTrain(paths, req, listenerFactory);
    }

    public static WorkspaceResult runNative(
            EnginePaths.Paths paths, EngineRequests.NativeRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineHosted.runNative(paths, req, listener);
    }

    public static BuildPlanResult runInstall(
            EnginePaths.Paths paths,
            EngineRequests.InstallRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut)
            throws IOException {
        return EngineHosted.runInstall(paths, req, listenerFactory, testResultOut);
    }

    public static EngineRequests.GitFetchOutcome runGitFetch(
            EnginePaths.Paths paths,
            EngineRequests.GitFetchRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runGitFetch(paths, req, listenerFactory);
    }

    public static EngineRequests.ToolResolveOutcome runToolResolve(
            EnginePaths.Paths paths,
            EngineRequests.ToolResolveRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runToolResolve(paths, req, listenerFactory);
    }

    public static EngineRequests.ScriptPrepareOutcome runScriptPrepare(
            EnginePaths.Paths paths,
            EngineRequests.ScriptPrepareRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runScriptPrepare(paths, req, listenerFactory);
    }

    public static BuildPlanResult runCacheMaintenance(
            EnginePaths.Paths paths,
            EngineRequests.CacheMaintRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            ObjIntConsumer<Boolean> onWait,
            EngineRequests.CacheMaintSummary[] summaryOut)
            throws IOException {
        return EngineHosted.runCacheMaintenance(paths, req, listenerFactory, onWait, summaryOut);
    }
}
