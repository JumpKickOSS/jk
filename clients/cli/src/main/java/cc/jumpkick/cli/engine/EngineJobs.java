// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.runtime.WorkspaceSpec;
import cc.jumpkick.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The engine's job-shaped build verbs: encode a request, send it, and replay the event stream the
 * engine writes back through {@link EngineEventDecoder}. Every method here is the engine-hosted
 * twin of something {@code BuildService} would otherwise run in-process, and drives the caller's
 * listener exactly as the in-process path would — per {@code docs/architecture.md} there is no
 * in-process fallback, so a failure here is a thrown error with the engine's message, never a
 * quiet degradation.
 *
 * <p>The split is by stream shape, not by verb. {@link #buildWorkspace}, {@link #runNative},
 * {@link #runImageWorkspace} and {@link #runCompileWorkspace} all speak the workspace vocabulary
 * (many plans keyed by module dir) whatever their request type; {@link #runTest}, {@link
 * #runSingleBuild} and {@link #runInstall} all speak the single-plan one. That is why the four
 * request types can share {@code buildWorkspace}'s plumbing without any of them knowing about the
 * others.
 */
final class EngineJobs {

    private EngineJobs() {}

    /**
     * Run {@code req}'s workspace build against the engine, spawning/reconnecting as needed, and
     * drive {@code listener} exactly as the engine's {@code BuildService.buildWorkspace} would
     * in-process.
     */
    static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener) throws IOException {
        Session session = SessionContext.current();
        return workspace(
                paths,
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                encodeWorkspaceRequest(req, session),
                                req.variant(),
                                req.clientEnv(),
                                session.jvm(),
                                // rebuild rides the session envelope: bypass jk's caches without implying
                                // refresh — verify's scratch rebuild stays CAS-local (no re-download).
                                session.config().rebuildOr(false),
                                TimelineOpts.noTimeline(),
                                session.assemblyOverride()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec(),
                        SessionContext.current().graalHome() == null
                                ? null
                                : SessionContext.current().graalHome().toString()),
                req.cache(),
                listener);
    }

    /**
     * The build-request body for {@code req} as the engine sees it (before the session envelope).
     * Session-owned facts ride from {@code session}: parallel-tests, offline/force, and the
     * resolved test selection — {@code --all}/{@code --include-tags}/{@code --exclude-tags} must
     * ride the wire or the engine falls back to each module's {@code [test]} excludes and a
     * widened tier is silently served from the unit-tier stamp (JK-2181).
     */
    static String encodeWorkspaceRequest(WorkspaceRequest req, Session session) {
        return withKeepGoing(
                withWorkspaceSpec(
                        ProtoJobs.buildRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.jdksDir() != null ? req.jdksDir().toString() : null,
                                req.workers(),
                                req.profile(),
                                req.skipTests(),
                                req.verbose(),
                                req.maxModuleConcurrency(),
                                session.parallelTests(),
                                session.offline(),
                                session.force(),
                                // jk build asks the engine to auto-freshen a stale workspace lock; verify's
                                // scratch rebuild must use the pinned lock verbatim (see WorkspaceRequest).
                                req.freshenLock(),
                                // verify's scratch rebuild: never persist action records under
                                // scratch-salted keys that can never recur.
                                req.ephemeralActions(),
                                // workspace jk test: every module plan stops at run-tests.
                                req.testOnly(),
                                // -m / --affected-since module selection — the engine schedules
                                // exactly these dirs instead of forecasting dirtiness itself.
                                req.dirtyHint() == null
                                        ? null
                                        : req.dirtyHint().stream()
                                                .map(Object::toString)
                                                .sorted()
                                                .toList(),
                                session.testSelection(),
                                req.modules()),
                        req),
                req);
    }

    /**
     * Additive {@code workspaceTarget} / {@code graalHomes} on a build-request. Omitted when the
     * spec is the default package basket so older engines see an unchanged body.
     */
    /**
     * Additive {@code keepGoing} on a build-request — omitted when false so a default run's body is
     * unchanged. See {@link WorkspaceRequest#keepGoing()}.
     */
    static String withKeepGoing(String json, WorkspaceRequest req) {
        if (!req.keepGoing()) return json;
        return json.substring(0, json.length() - 1) + ",\"keepGoing\":true}";
    }

    static String withWorkspaceSpec(String json, WorkspaceRequest req) {
        WorkspaceSpec spec = req.spec();
        if (spec == null || spec == WorkspaceSpec.DEFAULT) return json;
        if (spec.target() == WorkspaceTarget.PACKAGE && spec.graalByDir().isEmpty()) return json;
        StringBuilder extra = new StringBuilder();
        if (spec.target() != WorkspaceTarget.PACKAGE) {
            extra.append(",\"workspaceTarget\":")
                    .append(Jsonl.quote(spec.target().name().toLowerCase()));
        }
        if (!spec.graalByDir().isEmpty()) {
            LinkedHashMap<String, String> homes = new LinkedHashMap<>();
            spec.graalByDir().forEach((dir, home) -> homes.put(dir.toString(), home.toString()));
            extra.append(",\"graalHomes\":").append(Jsonl.map(homes));
        }
        if (extra.isEmpty()) return json;
        return json.substring(0, json.length() - 1) + extra + "}";
    }

    /**
     * Run a single project's test plan against the engine. {@code listenerFactory} builds the
     * console {@link BuildPlanListener} once the plan's step list is known (mirroring {@code
     * BuildPlanConsole.runBuildPlan}'s own mode-based listener choice, which also needs {@code
     * plan.steps} before it can construct a {@code CommandManagerListener}) — the wire doesn't have
     * a real {@code BuildPlan} to ask, so the steps arrive as their own small event burst first.
     * {@code testResultOut}, if non-null, is populated with the test-run counts (for exit-code and
     * summary logic) before the terminal {@code plan-finish} event reaches {@code
     * listenerFactory}'s listener, exactly mirroring how the in-process path's {@code
     * plan.get(TEST_RESULT)} is already populated by the time the console listener's own {@code
     * planFinish} fires.
     */
    static BuildPlanResult runTest(
            EnginePaths.Paths paths,
            EngineRequests.TestRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut)
            throws IOException {
        Session session = SessionContext.current();
        var sel = req.testSelection() != null ? req.testSelection() : session.testSelection();
        return singlePlan(
                paths,
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                ProtoJobs.testRequest(
                                        req.entryDir().toString(),
                                        req.cache().toString(),
                                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                                        req.workers(),
                                        req.profile(),
                                        req.verbose(),
                                        req.offline(),
                                        req.force(),
                                        req.parallelTests() || session.parallelTests(),
                                        sel),
                                session.variant(),
                                session.clientEnv(),
                                session.jvm(),
                                session.config().rebuildOr(false),
                                TimelineOpts.noTimeline()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec(),
                        SessionContext.current().graalHome() == null
                                ? null
                                : SessionContext.current().graalHome().toString()),
                listenerFactory,
                testResultOut,
                null);
    }

    /**
     * Run a single (non-workspace) project's real build plan against the engine — the counterpart of
     * {@code BuildCommand.runForDir}. Same shape as {@link #runTest}, plus {@code buildOutcomeOut}
     * (populated with {@code PlannerPackage.BUILD_OUTCOME}, if the plan reported one, before the
     * terminal {@code plan-finish} reaches {@code listenerFactory}'s listener) so the caller's
     * summary line (e.g. "project up to date" vs "project built") can match the in-process path.
     */
    static BuildPlanResult runSingleBuild(
            EnginePaths.Paths paths,
            EngineRequests.SingleBuildRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        Session session = SessionContext.current();
        return singlePlan(
                paths,
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                ProtoJobs.singleBuildRequest(
                                        req.entryDir().toString(),
                                        req.cache().toString(),
                                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                                        req.workers(),
                                        req.profile(),
                                        req.skipTests(),
                                        req.verbose(),
                                        req.offline(),
                                        req.force(),
                                        // jk build --all / tag flags on a single project (JK-2182).
                                        session.testSelection()),
                                req.variant(),
                                req.clientEnv(),
                                session.jvm(),
                                session.config().rebuildOr(false),
                                TimelineOpts.noTimeline(),
                                session.assemblyOverride()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec(),
                        SessionContext.current().graalHome() == null
                                ? null
                                : SessionContext.current().graalHome().toString()),
                listenerFactory,
                testResultOut,
                buildOutcomeOut);
    }

    /**
     * Run {@code jk native}'s hosted module cascade against the engine — it speaks {@link
     * EngineProtocol#BUILD_REQUEST}'s workspace event vocabulary (see {@link
     * EngineProtocol#NATIVE_REQUEST}), so the stream replays through the exact same {@link
     * WorkspaceBuildListener} plumbing {@link #buildWorkspace} uses. Module and workspace exit
     * codes are engine-computed ({@code jk native}'s 64/4/1 mapping).
     */
    static WorkspaceResult runNative(
            EnginePaths.Paths paths, EngineRequests.NativeRequest req, WorkspaceBuildListener listener)
            throws IOException {
        Map<String, String> graalHomes = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> e : req.graalByDir().entrySet()) {
            graalHomes.put(e.getKey().toString(), e.getValue().toString());
        }
        List<String> moduleDirs = new ArrayList<>();
        if (req.selectedModuleDirs() != null) {
            for (Path p : req.selectedModuleDirs()) {
                if (p != null) moduleDirs.add(p.toString());
            }
        }
        return workspace(
                paths,
                envelope(ProtoJobs.nativeRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                        req.mainClass(),
                        req.skipTests(),
                        req.offline(),
                        req.force(),
                        req.verbose(),
                        req.extraArgs(),
                        graalHomes,
                        moduleDirs)),
                req.cache(),
                listener);
    }

    /**
     * Workspace-member {@code jk image}: {@code IMAGE_REQUEST} on the module dir; the engine
     * expands the workspace cone and streams workspace events (same as {@link #runNative}).
     */
    static WorkspaceResult runImageWorkspace(
            EnginePaths.Paths paths, EngineRequests.ImageRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return workspace(
                paths,
                envelope(ProtoJobs.imageRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                        req.mainClass(),
                        req.registry(),
                        req.tag(),
                        req.tarballArg(),
                        req.dockerExecutable(),
                        req.skipTests(),
                        req.offline(),
                        req.force(),
                        req.verbose())),
                req.cache(),
                listener);
    }

    /**
     * Workspace {@code jk compile} (root or member): {@code COMPILE_REQUEST} on the entry dir;
     * the engine expands the cone (prereqs package, selection compiles-only) and streams
     * workspace events — the one-orchestrator COMPILE path (JK-2103).
     */
    static WorkspaceResult runCompileWorkspace(
            EnginePaths.Paths paths, EngineRequests.CompileRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return workspace(
                paths,
                envelope(ProtoJobs.compileRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.profile(),
                        req.offline(),
                        req.force(),
                        req.verbose(),
                        req.modules())),
                req.cache(),
                listener);
    }

    /**
     * Run {@code jk install}'s hosted build + cache-install plan against the engine — {@link
     * #runTest}'s exact shape ({@code testResultOut} settles before the terminal {@code
     * plan-finish} reaches the listener); the launcher-writing "make install" half runs in the
     * caller afterwards.
     */
    static BuildPlanResult runInstall(
            EnginePaths.Paths paths,
            EngineRequests.InstallRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut)
            throws IOException {
        return singlePlan(
                paths,
                envelope(ProtoJobs.installRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.m2Dir().toString(),
                        req.graalHome() != null ? req.graalHome().toString() : null,
                        req.skipTests(),
                        req.offline(),
                        req.force(),
                        req.verbose())),
                listenerFactory,
                testResultOut,
                null);
    }

    /**
     * The plain session envelope every job-shaped request rides: variant selection, client env,
     * worker-JVM tuning, rebuild, and the {@code --no-timeline} preference. An empty envelope
     * attaches nothing, so an unadorned request is byte-identical to the bare body.
     */
    private static String envelope(String body) {
        Session session = SessionContext.current();
        return ProtoSession.withToolchain(
                ProtoSession.withSession(
                        body,
                        session.variant(),
                        session.clientEnv(),
                        session.jvm(),
                        session.config().rebuildOr(false),
                        TimelineOpts.noTimeline()),
                SessionContext.current().jdkSpec(),
                SessionContext.current().graalSpec(),
                SessionContext.current().graalHome() == null
                        ? null
                        : SessionContext.current().graalHome().toString());
    }

    /** Send {@code requestLine} and replay the workspace event stream into {@code listener}. */
    private static WorkspaceResult workspace(
            EnginePaths.Paths paths, String requestLine, Path cache, WorkspaceBuildListener listener)
            throws IOException {
        return EngineWire.stream(
                paths,
                requestLine,
                (reader, ch) -> EngineEventDecoder.streamWorkspaceEvents(reader, listener, cache, ch));
    }

    /** Send {@code requestLine} and replay the single-plan event stream into the built listener. */
    private static BuildPlanResult singlePlan(
            EnginePaths.Paths paths,
            String requestLine,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return EngineWire.stream(
                paths,
                requestLine,
                (reader, ch) -> EngineEventDecoder.streamSingleBuildPlanEvents(
                        reader, listenerFactory, testResultOut, buildOutcomeOut, ch));
    }
}
