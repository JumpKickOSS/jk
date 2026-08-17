// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.EngineWireException;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.ModuleOutcome;
import cc.jumpkick.runtime.ModulePlan;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Engine-hosted workspace build: send {@link EngineProtocol#BUILD_REQUEST}, decode wire events into
 * the caller's {@link WorkspaceBuildListener}/{@link BuildPlanListener}. Client {@link ModulePlan}
 * uses inert steps for name/steps only.
 */
final class EngineBuildListenerAdapter {

    private EngineBuildListenerAdapter() {}

    /** Bind CLI details.jsonl to the engine journal run from a {@code job-start} line. */
    private static void bindTranscript(String jobStartLine) {
        CliSessionTranscript s = CliSessionTranscript.active();
        if (s == null || jobStartLine == null) return;
        long jid = Jsonl.longValue(jobStartLine, "jid", -1);
        long buildNumber = Jsonl.longValue(jobStartLine, "buildNumber", 0);
        String detailsPath = Jsonl.str(jobStartLine, "detailsPath");
        long etaMs = Jsonl.longValue(jobStartLine, "etaMs", -1);
        s.bindJob(jid, buildNumber, detailsPath, etaMs);
    }

    /** One module's identity/sizing, accumulated from the {@code plan-module}/{@code plan-step} burst. */
    private static final class ModuleMeta {
        final String coord;
        final String planName;
        final int weight;
        final boolean fullyCached;
        final List<Task> steps = new ArrayList<>();

        ModuleMeta(String coord, String planName, int weight, boolean fullyCached) {
            this.coord = coord;
            this.planName = planName;
            this.weight = weight;
            this.fullyCached = fullyCached;
        }
    }

    /**
     * Run {@code req} against the engine at {@code paths}, spawning/reconnecting as needed, and drive
     * {@code listener} exactly as the engine's {@code BuildService.buildWorkspace} would in-process. Throws with a
     * clear message on any engine-unreachable/protocol failure — per {@code docs/architecture.md} there is
     * no in-process fallback.
     */
    static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        Session session = SessionContext.current();

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(ProtoSession.withSession(
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
                            null,
                            req.modules()),
                    req.variant(),
                    req.clientEnv(),
                    SessionContext.current().jvm(),
                    // rebuild rides the session envelope: bypass jk's caches without implying
                    // refresh — verify's scratch rebuild stays CAS-local (no re-download).
                    session.config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline(),
                    SessionContext.current().assemblyOverride()));
            writer.write('\n');
            writer.flush();

            return streamEvents(reader, listener, req.cache());
        }
    }

    /**
     * Run a single project's test plan against the engine (Task 3). {@code listenerFactory} builds
     * the actual console {@link BuildPlanListener} once the plan's step list is known (mirroring {@code
     * BuildPlanConsole.runBuildPlan}'s own mode-based listener choice, which also needs {@code plan.steps}
     * before it can construct a {@code CommandManagerListener}) — the wire doesn't have a real {@code
     * BuildPlan} to ask, so the steps arrive as their own small event burst first. {@code testResultOut},
     * if non-null, is populated with the test-run counts (for exit-code/summary logic) before the
     * terminal {@code plan-finish} event reaches {@code listenerFactory}'s listener, exactly mirroring
     * how the in-process path's {@code plan.get(TEST_RESULT)} is already populated by the time the
     * console listener's own {@code planFinish} fires.
     */
    static BuildPlanResult runTest(
            EnginePaths.Paths paths,
            EngineRequests.TestRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            var sel = req.testSelection() != null
                    ? req.testSelection()
                    : SessionContext.current().testSelection();
            writer.write(ProtoSession.withSession(
                    ProtoJobs.testRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.workers(),
                            req.profile(),
                            req.verbose(),
                            req.offline(),
                            req.force(),
                            req.parallelTests() || SessionContext.current().parallelTests(),
                            sel),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline()));
            writer.write('\n');
            writer.flush();

            return streamSingleBuildPlanEvents(reader, listenerFactory, testResultOut, null);
        }
    }

    /**
     * Run a single (non-workspace) project's real build plan against the engine — the counterpart of
     * {@code BuildCommand.runForDir}. Same shape as {@link #runTest}, plus {@code buildOutcomeOut}
     * (populated with {@code BuildPlanner.BUILD_OUTCOME}, if the plan reported one, before the
     * terminal {@code plan-finish} reaches {@code listenerFactory}'s listener) so the caller's
     * summary line (e.g. "project up to date" vs "project built") can match the in-process path.
     */
    static BuildPlanResult runSingleBuild(
            EnginePaths.Paths paths,
            EngineRequests.SingleBuildRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(ProtoSession.withSession(
                    ProtoJobs.singleBuildRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.workers(),
                            req.profile(),
                            req.skipTests(),
                            req.verbose(),
                            req.offline(),
                            req.force()),
                    req.variant(),
                    req.clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline(),
                    SessionContext.current().assemblyOverride()));
            writer.write('\n');
            writer.flush();

            return streamSingleBuildPlanEvents(reader, listenerFactory, testResultOut, buildOutcomeOut);
        }
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
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

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
            writer.write(ProtoSession.withSession(
                    ProtoJobs.nativeRequest(
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
                            moduleDirs),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline()));
            writer.write('\n');
            writer.flush();

            return streamEvents(reader, listener, req.cache());
        }
    }

    /**
     * Workspace-member {@code jk image}: {@code IMAGE_REQUEST} on the module dir; the engine
     * expands the workspace cone and streams workspace events (same as {@link #runNative}).
     */
    static WorkspaceResult runImageWorkspace(
            EnginePaths.Paths paths, EngineRequests.ImageRequest req, WorkspaceBuildListener listener)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(ProtoSession.withSession(
                    ProtoJobs.imageRequest(
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
                            req.verbose()),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline()));
            writer.write('\n');
            writer.flush();
            return streamEvents(reader, listener, req.cache());
        }
    }

    /**
     * Workspace {@code jk compile} (root or member): {@code COMPILE_REQUEST} on the entry dir;
     * the engine expands the cone (prereqs package, selection compiles-only) and streams
     * workspace events — the one-orchestrator COMPILE path (JK-2103).
     */
    static WorkspaceResult runCompileWorkspace(
            EnginePaths.Paths paths, EngineRequests.CompileRequest req, WorkspaceBuildListener listener)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(ProtoSession.withSession(
                    ProtoJobs.compileRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.profile(),
                            req.offline(),
                            req.force(),
                            req.verbose(),
                            req.modules()),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline()));
            writer.write('\n');
            writer.flush();
            return streamEvents(reader, listener, req.cache());
        }
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
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(ProtoSession.withSession(
                    ProtoJobs.installRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.m2Dir().toString(),
                            req.graalHome() != null ? req.graalHome().toString() : null,
                            req.skipTests(),
                            req.offline(),
                            req.force(),
                            req.verbose()),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false),
                    cc.jumpkick.cli.run.TimelineOpts.noTimeline()));
            writer.write('\n');
            writer.flush();

            return streamSingleBuildPlanEvents(reader, listenerFactory, testResultOut, null);
        }
    }

    /**
     * Forecast a build against the engine — the counterpart of {@code ExplainCommand}'s direct {@code
     * BuildService.explain} call. Synchronous: sends {@link EngineProtocol#EXPLAIN_REQUEST} and reads
     * the module/step/edge burst to completion, reconstructing a real {@link ExplainPlan}.
     * Unlike {@link #buildModulePlan}, no inert-object trickery is needed here — {@link
     * cc.jumpkick.runtime.TaskForecast.Module}/{@code Task} are pure public data, reconstructed
     * via {@code Module.fromWire}, exactly as {@link #buildModulePlan} does with {@code
     * ModulePlan.fromWire} ({@code Module.unit} is package-private and never read here).
     *
     * <p>Plan-affecting build options ride the request for the engine-side ETA estimate ({@code
     * eta} event; {@code 0} = unknown) before {@code explain-done}.
     */
    static ExplainPlan explain(EnginePaths.Paths paths, EngineRequests.ExplainRequest req, long[] etaOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(ProtoReads.explainRequest(
                    req.entryDir().toString(),
                    req.cache().toString(),
                    req.workers(),
                    req.skipTests(),
                    req.profile(),
                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                    req.serial(),
                    req.parallelTests(),
                    req.verbose(),
                    req.rebuild(),
                    req.maxModuleConcurrency()));
            writer.write('\n');
            writer.flush();

            List<cc.jumpkick.runtime.TaskForecast.Module> modules = new ArrayList<>();
            Map<String, List<cc.jumpkick.runtime.TaskForecast.Task>> stepsByDir = new LinkedHashMap<>();
            Map<String, String> coordByDir = new LinkedHashMap<>();
            Map<String, int[]> countsByDir = new LinkedHashMap<>(); // [sourceCount, testCount]
            Map<String, boolean[]> flagsByDir = new LinkedHashMap<>(); // [producesJar, producesImage]
            List<String> order = new ArrayList<>();
            Map<Path, Set<Path>> edges = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            String line;
            long notedJid = -1;
            try {
                while ((line = reader.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    if (type == null) continue;
                    if (EngineProtocol.JOB_START.equals(type)) {
                        notedJid = Jsonl.longValue(line, "jid", -1);
                        cc.jumpkick.cli.engine.EngineClient.ActiveJobs.note(notedJid);
                        bindTranscript(line);
                        continue;
                    }
                    switch (type) {
                        case EngineProtocol.EXPLAIN_MODULE -> {
                            String dir = Jsonl.str(line, "dir");
                            order.add(dir);
                            coordByDir.put(dir, Jsonl.str(line, "coord"));
                            countsByDir.put(dir, new int[] {
                                Jsonl.intValue(line, "sourceCount", 0), Jsonl.intValue(line, "testCount", 0)
                            });
                            flagsByDir.put(dir, new boolean[] {
                                Jsonl.bool(line, "producesJar", false), Jsonl.bool(line, "producesImage", false)
                            });
                            stepsByDir.put(dir, new ArrayList<>());
                        }
                        case EngineProtocol.EXPLAIN_TASK -> {
                            String dir = Jsonl.str(line, "dir");
                            stepsByDir
                                    .get(dir)
                                    .add(new cc.jumpkick.runtime.TaskForecast.Task(
                                            Jsonl.str(line, "name"),
                                            cc.jumpkick.runtime.TaskForecast.Status.valueOf(Jsonl.str(line, "status")),
                                            Jsonl.str(line, "text"),
                                            Jsonl.str(line, "key")));
                        }
                        case EngineProtocol.EXPLAIN_EDGE -> {
                            Path dir = Path.of(Jsonl.str(line, "dir"));
                            Path dependsOn = Path.of(Jsonl.str(line, "dependsOnDir"));
                            edges.computeIfAbsent(dir, d -> new LinkedHashSet<>())
                                    .add(dependsOn);
                        }
                        case EngineProtocol.ERROR -> errors.add(Jsonl.str(line, "message"));
                        case EngineProtocol.ETA -> {
                            if (etaOut != null) {
                                etaOut[0] = Jsonl.longValue(line, "millis", 0);
                                // Optional full-rebuild ETA (explain effort denominator); 0 when absent.
                                if (etaOut.length > 1) {
                                    etaOut[1] = Jsonl.longValue(line, "fullMillis", 0);
                                }
                            }
                        }
                        case EngineProtocol.EXPLAIN_DONE -> {
                            for (String dir : order) {
                                int[] counts = countsByDir.get(dir);
                                boolean[] flags = flagsByDir.get(dir);
                                modules.add(cc.jumpkick.runtime.TaskForecast.Module.fromWire(
                                        Path.of(dir),
                                        coordByDir.get(dir),
                                        stepsByDir.get(dir),
                                        counts[0],
                                        counts[1],
                                        flags[0],
                                        flags[1]));
                            }
                            return new ExplainPlan(modules, edges, Jsonl.intValue(line, "maxReadyWidth", 1), errors);
                        }
                        default -> {
                            /* forward-compatible no-op */
                        }
                    }
                }
                throw new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                        + "(it may have crashed); run `jk engine status` for details");
            } finally {
                // The job is over however the stream ended — a stale jid here would add a 2s
                // cancel RPC to every future Ctrl-C in this process.
                if (notedJid > 0) cc.jumpkick.cli.engine.EngineClient.ActiveJobs.forget(notedJid);
            }
        }
    }

    /** Decodes one matched ack line into its reply value. */
    interface AckDecoder<T> {
        T decode(String line) throws IOException;
    }

    /**
     * One synchronous request/ack exchange: write {@code requestLine}, skip stream noise until the
     * {@code ackType} line, return its decoded value. Every read-only engine verb goes through here
     * so the discriminator is matched in exactly one place.
     */
    static <T> T request(
            EnginePaths.Paths paths, String requestLine, String ackType, String what, AckDecoder<T> decoder)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(requestLine);
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!ackType.equals(EngineProtocol.typeOf(line))) continue;
                return decoder.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the " + what);
        }
    }

    /** One engine-hosted jk.toml edit: returns changed; throws with the engine's message. */
    static boolean edit(EnginePaths.Paths paths, Path file, String op, List<String> args) throws IOException {
        return request(
                paths,
                ProtoReads.editRequest(file.toString(), op, args),
                EngineProtocol.EDIT_ACK,
                "edit request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    return Jsonl.bool(line, "changed", false);
                });
    }

    static cc.jumpkick.engine.protocol.PluginInstallLocalAck pluginInstallLocal(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            Path installRoot,
            String modules,
            boolean dryRun,
            boolean ambientStore)
            throws IOException {
        return request(
                paths,
                ProtoReads.pluginInstallLocalRequest(
                        dir.toString(),
                        cache.toString(),
                        installRoot == null ? "" : installRoot.toString(),
                        modules,
                        dryRun,
                        ambientStore),
                EngineProtocol.PLUGIN_INSTALL_LOCAL_ACK,
                "plugin install-local",
                cc.jumpkick.engine.protocol.PluginInstallLocalAck::decode);
    }

    static String editDetail(EnginePaths.Paths paths, Path file, String op, List<String> args) throws IOException {
        return request(
                paths,
                ProtoReads.editRequest(file.toString(), op, args),
                EngineProtocol.EDIT_ACK,
                "edit request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    String detail = Jsonl.str(line, "detail");
                    return detail == null ? "" : detail;
                });
    }

    /**
     * On-demand engine-hosted catalog freshen ({@code templates}/{@code libraries}/{@code jdks}) —
     * the CLI never touches these catalogs' networks itself once an engine is available. Callers
     * decide separately whether it's fine to start an engine for this ({@link
     * EngineClient#freshenCatalog}) or whether an already-running one is required ({@link
     * EngineClient#freshenCatalogIfRunning}, for {@code jk jdk install}/{@code update}'s bootstrap
     * case) — this method itself just sends the request. Best-effort: swallows the engine's error
     * rather than throwing, since the caller falls back to whatever the local cache already holds.
     */
    static void freshenCatalog(EnginePaths.Paths paths, String catalog, boolean offline, String url, String cacheFile) {
        try {
            request(
                    paths,
                    ProtoReads.freshenCatalogRequest(catalog, offline, url, cacheFile),
                    EngineProtocol.FRESHEN_CATALOG_ACK,
                    catalog + " freshen request",
                    line -> Jsonl.bool(line, "ok", false));
        } catch (IOException ignored) {
            // Best-effort — local resolution proceeds against whatever the cache already holds.
        }
    }

    /** One engine-hosted tree render: the marker-tagged tree; throws with the engine's message. */
    static String treeRender(
            EnginePaths.Paths paths, Path dir, int maxDepth, boolean flatten, boolean stack, List<String> scopes)
            throws IOException {
        return request(
                paths,
                ProtoReads.treeRequest(dir.toString(), maxDepth, flatten, stack, scopes),
                EngineProtocol.TREE_ACK,
                "tree request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    return Jsonl.str(line, "rendered");
                });
    }

    /** One engine-hosted why lookup. */
    static cc.jumpkick.engine.protocol.WhyReport why(EnginePaths.Paths paths, Path dir, String query)
            throws IOException {
        return request(
                paths,
                ProtoReads.whyRequest(dir.toString(), query),
                EngineProtocol.WHY_ACK,
                "why request",
                cc.jumpkick.engine.protocol.WhyReport::decode);
    }

    /** One engine-hosted IDE model computation: the wire model back, generation stays client-side. */
    static cc.jumpkick.engine.protocol.IdeWireModel ideModel(
            EnginePaths.Paths paths, Path dir, Path cache, Path jdksDir) throws IOException {
        return request(
                paths,
                ProtoReads.ideModelRequest(
                        dir.toString(), cache.toString(), jdksDir == null ? null : jdksDir.toString()),
                EngineProtocol.IDE_MODEL_ACK,
                "ide-model request",
                cc.jumpkick.engine.protocol.IdeWireModel::decode);
    }

    static cc.jumpkick.engine.protocol.NewProjectAck newProject(
            EnginePaths.Paths paths, EngineRequests.NewProjectRequest req) throws IOException {
        return request(
                paths,
                ProtoReads.newProjectRequest(
                        req.name(),
                        req.parentDir(),
                        req.group(),
                        req.lang(),
                        req.layout(),
                        req.template(),
                        req.executable(),
                        req.framework(),
                        req.jdk(),
                        req.javaRelease(),
                        req.assembly(),
                        req.nativeImage(),
                        req.plugin(),
                        req.kotlinModule(),
                        req.deps(),
                        req.sample(),
                        req.standalone(),
                        req.templateParams(),
                        req.relaxParent()),
                EngineProtocol.NEW_PROJECT_ACK,
                "new-project request",
                cc.jumpkick.engine.protocol.NewProjectAck::decode);
    }

    /** One engine-hosted generator run: file payloads back, guards/writes stay client-side. */
    static cc.jumpkick.engine.protocol.GeneratedFiles generate(
            EnginePaths.Paths paths, Path dir, String kind, Map<String, String> params) throws IOException {
        return request(
                paths,
                ProtoReads.generateRequest(dir.toString(), kind, params),
                EngineProtocol.GENERATE_ACK,
                "generate request",
                cc.jumpkick.engine.protocol.GeneratedFiles::decode);
    }

    /** One engine-hosted plugin command run. */
    static cc.jumpkick.engine.protocol.PluginCommandReport pluginCommand(
            EnginePaths.Paths paths, Path dir, Path cache, String command, List<String> args) throws IOException {
        return request(
                paths,
                ProtoSession.withSession(
                        ProtoReads.pluginCommandRequest(dir.toString(), cache.toString(), command, args),
                        SessionContext.current().variant(),
                        SessionContext.current().clientEnv(),
                        SessionContext.current().jvm(),
                        SessionContext.current().config().rebuildOr(false),
                        cc.jumpkick.cli.run.TimelineOpts.noTimeline()),
                EngineProtocol.PLUGIN_VERB_ACK,
                "plugin command",
                cc.jumpkick.engine.protocol.PluginCommandReport::decode);
    }

    /** One engine-hosted deny check: policy parse + lock read + violations, engine-side. */
    static cc.jumpkick.engine.protocol.DenyReport denyCheck(EnginePaths.Paths paths, Path dir) throws IOException {
        return request(
                paths,
                ProtoReads.denyCheckRequest(dir.toString()),
                EngineProtocol.DENY_CHECK_ACK,
                "deny check",
                cc.jumpkick.engine.protocol.DenyReport::decode);
    }

    static cc.jumpkick.engine.protocol.ProjectInfo projectInfo(EnginePaths.Paths paths, Path dir) throws IOException {
        return projectInfo(paths, dir, null, null);
    }

    static cc.jumpkick.engine.protocol.ProjectInfo projectInfo(
            EnginePaths.Paths paths, Path dir, String modules, String affectedSince) throws IOException {
        return request(
                paths,
                ProtoReads.projectInfoRequest(dir.toString(), "", modules, affectedSince),
                EngineProtocol.PROJECT_INFO_ACK,
                "project-info request",
                cc.jumpkick.engine.protocol.ProjectInfo::decode);
    }

    static cc.jumpkick.engine.protocol.ExecPlan execPlan(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            String mainOverride,
            String binName,
            Path binDir,
            Path libDir)
            throws IOException {
        return request(
                paths,
                ProtoSession.withSession(
                        ProtoReads.execPlanRequest(
                                dir.toString(),
                                cache.toString(),
                                kind,
                                mainOverride,
                                binName,
                                binDir == null ? null : binDir.toString(),
                                libDir == null ? null : libDir.toString()),
                        SessionContext.current().variant(),
                        SessionContext.current().clientEnv(),
                        SessionContext.current().jvm(),
                        SessionContext.current().config().rebuildOr(false),
                        cc.jumpkick.cli.run.TimelineOpts.noTimeline()),
                EngineProtocol.EXEC_PLAN_ACK,
                "exec-plan request",
                cc.jumpkick.engine.protocol.ExecPlan::decode);
    }

    /**
     * Pre-flight a build's dirty forecast against the engine ({@code jk build}'s fully-cached
     * shortcut + dirty hint — see {@link EngineProtocol#FORECAST_REQUEST}). Synchronous: one
     * request line, one {@code forecast-ack} back. The session's offline/force/rerun flags ride
     * the request so the engine's forecast honors them exactly as the in-process one did.
     */
    static cc.jumpkick.runtime.BuildForecast forecast(
            EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests) throws IOException {
        Session session = SessionContext.current();
        return request(
                paths,
                ProtoReads.forecastRequest(
                        entryDir.toString(),
                        cache.toString(),
                        skipTests,
                        session.offline(),
                        session.force(),
                        session.config().rebuildOr(false)),
                EngineProtocol.FORECAST_ACK,
                "forecast request",
                line -> {
                    Set<Path> dirty = new LinkedHashSet<>();
                    for (String d : Jsonl.strArray(line, "dirtyDirs")) dirty.add(Path.of(d));
                    return new cc.jumpkick.runtime.BuildForecast(
                            dirty,
                            Jsonl.bool(line, "lockStale", false),
                            Jsonl.bool(line, "empty", false),
                            Jsonl.strArray(line, "errors"));
                });
    }

    // Package-visible for tests (ActiveJobs lifecycle, cancel terminals —.
    static BuildPlanResult streamSingleBuildPlanEvents(
            BufferedReader reader,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        List<Task> steps = new ArrayList<>();
        List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
        BuildPlanListener listener = null;
        // The wire carries no duration; the summary's "took …" is this client-side
        // wall clock over the whole stream (spawn latency excluded — ensureRunning
        // already returned before the request was written).
        long startNanos = System.nanoTime();

        String line;
        long notedJid = -1;
        try {
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                if (EngineProtocol.JOB_START.equals(type)) {
                    notedJid = Jsonl.longValue(line, "jid", -1);
                    cc.jumpkick.cli.engine.EngineClient.ActiveJobs.note(notedJid);
                    bindTranscript(line);
                    continue;
                }
                // Same pre-listener contract as EnginePluginAdapter/EngineResolveAdapter: until
                // plan-done constructs the listener, keep diagnostics and drop everything else —
                // a cancel injected from another thread can land events out of order.
                if (listener == null
                        && !EngineProtocol.PLAN_TASK.equals(type)
                        && !EngineProtocol.PLAN_DONE.equals(type)
                        && !EngineProtocol.BUILDPLAN_FINISH.equals(type)
                        && !EngineProtocol.ERROR.equals(type)) {
                    if (EngineProtocol.BUILDPLAN_DIAGNOSTIC.equals(type)) {
                        diagnostics.add(diagnosticFromWire(line));
                    }
                    continue;
                }
                switch (type) {
                    case EngineProtocol.PLAN_TASK ->
                        steps.add(Task.builder(Jsonl.str(line, "name"))
                                .label(Jsonl.str(line, "label"))
                                .phase(wireGroup(Jsonl.str(line, "stage")))
                                .build());
                    case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                    case EngineProtocol.BUILDPLAN_START -> listener.planStart(readBuildPlanView(line));
                    case EngineProtocol.TASK_START ->
                        listener.stepStart(
                                Jsonl.str(line, "task"),
                                wireGroup(Jsonl.str(line, "stage")),
                                Jsonl.intValue(line, "ticks", 0));
                    case EngineProtocol.PROGRESS ->
                        listener.progress(
                                Jsonl.str(line, "task"), Jsonl.intValue(line, "delta", 0), readBuildPlanView(line));
                    case EngineProtocol.TICK_UPDATE ->
                        listener.tickUpdate(
                                Jsonl.str(line, "task"), Jsonl.intValue(line, "delta", 0), readBuildPlanView(line));
                    case EngineProtocol.LABEL -> listener.label(Jsonl.str(line, "task"), Jsonl.str(line, "label"));
                    case EngineProtocol.OUTPUT -> listener.output(Jsonl.str(line, "task"), Jsonl.str(line, "line"));
                    case EngineProtocol.WARN ->
                        listener.warn(Jsonl.str(line, "task"), Jsonl.str(line, "code"), Jsonl.str(line, "message"));
                    case EngineProtocol.ERROR_LINE -> dispatchError(listener, line);
                    case EngineProtocol.BUILDPLAN_DIAGNOSTIC -> diagnostics.add(diagnosticFromWire(line));
                    case EngineProtocol.TASK_FINISH ->
                        listener.stepFinish(
                                Jsonl.str(line, "task"),
                                wireGroup(Jsonl.str(line, "stage")),
                                TaskStatus.valueOf(Jsonl.str(line, "status")),
                                Duration.ofMillis(Jsonl.longValue(line, "millis", 0)));
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        boolean success = Jsonl.bool(line, "success", false);
                        long total = Jsonl.longValue(line, "testTotal", -1);
                        if (total >= 0 && testResultOut != null) {
                            testResultOut[0] = new cc.jumpkick.run.TestSummary(
                                    total,
                                    Jsonl.longValue(line, "testSucceeded", 0),
                                    Jsonl.longValue(line, "testFailed", 0),
                                    Jsonl.longValue(line, "testSkipped", 0),
                                    List.of());
                        }
                        if (buildOutcomeOut != null) {
                            buildOutcomeOut[0] = Jsonl.str(line, "buildOutcome");
                        }
                        boolean cancelled = Jsonl.bool(line, "cancelled", false);
                        BuildPlanResult result = new BuildPlanResult(
                                "test",
                                success,
                                Duration.ofNanos(System.nanoTime() - startNanos),
                                List.of(),
                                List.of(),
                                diagnostics,
                                cancelled,
                                cancelled);
                        // A remote cancel injects this terminal from another thread — it can land
                        // before plan-done ever created the listener.
                        if (listener != null) listener.planFinish(result);
                        return result;
                    }
                    case EngineProtocol.ERROR ->
                        throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
            }
            throw disconnectFailure();
        } finally {
            // The job is over however the stream ended.
            if (notedJid > 0) cc.jumpkick.cli.engine.EngineClient.ActiveJobs.forget(notedJid);
        }
    }

    /**
     * Bare EOF without a terminal line: a crash — unless this process already asked for cancel
     * (Ctrl-C's cooperative token), in which case the disconnect IS the cancel settling.
     */
    private static IOException disconnectFailure() {
        try {
            if (cc.jumpkick.config.SessionContext.current().cancelled()) {
                return new JobCancelledException();
            }
        } catch (RuntimeException ignored) {
            // no session installed — fall through to the crash message
        }
        return new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk engine status` for details");
    }

    private static WorkspaceResult streamEvents(BufferedReader reader, WorkspaceBuildListener listener, Path cache)
            throws IOException {
        Map<String, ModuleMeta> planByDir = new LinkedHashMap<>();
        Map<String, BuildPlanListener> planListenersByDir = new LinkedHashMap<>();
        Map<String, List<BuildPlanResult.Diagnostic>> diagnosticsByDir = new LinkedHashMap<>();
        List<ModuleOutcome> outcomes = new ArrayList<>();
        String pendingPlanDir = null; // the dir most recently opened by plan-module, for plan-step lines

        String line;
        long notedJid = -1;
        try {
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                if (EngineProtocol.JOB_START.equals(type)) {
                    notedJid = Jsonl.longValue(line, "jid", -1);
                    cc.jumpkick.cli.engine.EngineClient.ActiveJobs.note(notedJid);
                    bindTranscript(line);
                    continue;
                }
                String dir = Jsonl.str(line, "dir");
                switch (type) {
                    case EngineProtocol.PLAN_MODULE -> {
                        planByDir.put(
                                dir,
                                new ModuleMeta(
                                        Jsonl.str(line, "coord"),
                                        Jsonl.str(line, "planName"),
                                        Jsonl.intValue(line, "weight", 0),
                                        Jsonl.bool(line, "fullyCached", false)));
                        pendingPlanDir = dir;
                    }
                    case EngineProtocol.PLAN_TASK -> {
                        ModuleMeta m = planByDir.get(dir != null ? dir : pendingPlanDir);
                        if (m != null) {
                            m.steps.add(Task.builder(Jsonl.str(line, "name"))
                                    .label(Jsonl.str(line, "label"))
                                    .phase(wireGroup(Jsonl.str(line, "stage")))
                                    .build());
                        }
                    }
                    case EngineProtocol.PREFLIGHT ->
                        listener.onPreflight(
                                Jsonl.str(line, "stage"),
                                Jsonl.intValue(line, "done", 0),
                                Jsonl.intValue(line, "total", 0),
                                Jsonl.str(line, "label"));
                    case EngineProtocol.WORKSPACE_PROGRESS -> {
                        long num = Jsonl.longValue(line, "numerator", 0);
                        long den = Jsonl.longValue(line, "denominator", 0);
                        String phase = Jsonl.str(line, "phase");
                        int mc = Jsonl.intValue(line, "modulesComplete", 0);
                        int mt = Jsonl.intValue(line, "modulesTotal", 0);
                        long rem = Jsonl.longValue(line, "remainingMs", -1);
                        long r0 = Jsonl.longValue(line, "R0", 0);
                        // Prefer engine strategy percent (clock when R0 set); fall back to num/den.
                        double pct = Jsonl.has(line, "progress")
                                ? Jsonl.doubleValue(line, "progress", Double.NaN)
                                : (den > 0
                                        ? cc.jumpkick.runtime.WorkspaceProgressTracker.percentOf(num, den)
                                        : Double.NaN);
                        if (Double.isNaN(pct) && den > 0) {
                            pct = cc.jumpkick.runtime.WorkspaceProgressTracker.percentOf(num, den);
                        }
                        listener.onWorkspaceProgress(new cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot(
                                num, den, pct, phase == null ? "" : phase, mc, mt, rem, r0));
                        // Residual remainingMs rides the snapshot; AggregateContext re-anchors
                        // the countdown + adaptive bar (seed path stays on eta events only).
                    }
                    case EngineProtocol.PLAN_DONE -> listener.onPlan(buildModulePlans(planByDir, cache));
                    case EngineProtocol.ETA -> {
                        // Seed / re-seed only (R0). Client locks after execute starts.
                        long rem = Jsonl.longValue(line, "remainingMs", -1);
                        if (rem < 0) rem = Jsonl.longValue(line, "millis", 0);
                        listener.onEtaEstimate(rem);
                    }
                    case EngineProtocol.MODULE_START -> {
                        ModulePlan plan = buildModulePlan(dir, planByDir.get(dir), cache);
                        BuildPlanListener gl = listener.onModuleStart(plan);
                        planListenersByDir.put(dir, gl != null ? gl : new BuildPlanListener() {});
                    }
                    case EngineProtocol.BUILDPLAN_START ->
                        planListenersByDir.getOrDefault(dir, NOOP).planStart(readBuildPlanView(line));
                    case EngineProtocol.TASK_START ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .stepStart(
                                        Jsonl.str(line, "task"),
                                        wireGroup(Jsonl.str(line, "stage")),
                                        Jsonl.intValue(line, "ticks", 0));
                    case EngineProtocol.PROGRESS ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .progress(
                                        Jsonl.str(line, "task"),
                                        Jsonl.intValue(line, "delta", 0),
                                        readBuildPlanView(line));
                    case EngineProtocol.TICK_UPDATE ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .tickUpdate(
                                        Jsonl.str(line, "task"),
                                        Jsonl.intValue(line, "delta", 0),
                                        readBuildPlanView(line));
                    case EngineProtocol.LABEL ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .label(Jsonl.str(line, "task"), Jsonl.str(line, "label"));
                    case EngineProtocol.OUTPUT ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .output(Jsonl.str(line, "task"), Jsonl.str(line, "line"));
                    case EngineProtocol.WARN ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .warn(Jsonl.str(line, "task"), Jsonl.str(line, "code"), Jsonl.str(line, "message"));
                    case EngineProtocol.ERROR_LINE -> dispatchError(planListenersByDir.getOrDefault(dir, NOOP), line);
                    case EngineProtocol.BUILDPLAN_DIAGNOSTIC ->
                        diagnosticsByDir
                                .computeIfAbsent(dir, d -> new ArrayList<>())
                                .add(diagnosticFromWire(line));
                    case EngineProtocol.TASK_FINISH ->
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .stepFinish(
                                        Jsonl.str(line, "task"),
                                        wireGroup(Jsonl.str(line, "stage")),
                                        TaskStatus.valueOf(Jsonl.str(line, "status")),
                                        Duration.ofMillis(Jsonl.longValue(line, "millis", 0)));
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        ModuleMeta meta = planByDir.get(dir);
                        String planName = meta != null ? meta.planName : dir;
                        List<BuildPlanResult.Diagnostic> diags = diagnosticsByDir.remove(dir);
                        boolean cancelled = Jsonl.bool(line, "cancelled", false);
                        BuildPlanResult result = new BuildPlanResult(
                                planName,
                                Jsonl.bool(line, "success", false),
                                Duration.ZERO,
                                List.of(),
                                List.of(),
                                diags != null ? diags : List.of(),
                                cancelled,
                                cancelled);
                        planListenersByDir.getOrDefault(dir, NOOP).planFinish(result);
                    }
                    case EngineProtocol.MODULE_FINISH -> {
                        // didWork defaults true for older engines that omit the field (fail-open "built").
                        ModuleOutcome outcome = new ModuleOutcome(
                                Jsonl.str(line, "coord"),
                                Path.of(dir),
                                Jsonl.bool(line, "success", false),
                                Jsonl.intValue(line, "exitCode", 1),
                                Jsonl.longValue(line, "millis", 0),
                                Jsonl.bool(line, "didWork", true),
                                Jsonl.bool(line, "cancelled", false));
                        if (Jsonl.bool(line, "hasImage", false)) {
                            outcome = outcome.withImage(new ModuleOutcome.Image(
                                    Jsonl.str(line, "imageRef"),
                                    Jsonl.str(line, "imageTarball"),
                                    Jsonl.str(line, "imageName"),
                                    Jsonl.str(line, "imageVersion"),
                                    Jsonl.str(line, "imageDaemonExe")));
                        }
                        outcomes.add(outcome);
                        listener.onModuleFinish(outcome);
                    }
                    case EngineProtocol.WORKSPACE_FINISH -> {
                        WorkspaceResult result = new WorkspaceResult(
                                Jsonl.bool(line, "success", false),
                                Jsonl.intValue(line, "exitCode", 1),
                                List.copyOf(outcomes),
                                Jsonl.strArray(line, "errors"),
                                Jsonl.bool(line, "cancelled", false));
                        listener.onWorkspaceFinish(result);
                        return result;
                    }
                    case EngineProtocol.ERROR -> {
                        EngineWireException wire = EngineWireException.fromJsonLine(line);
                        // surface as the wedge message body without engine noise.
                        if (wire.alreadyRunning()) {
                            String msg = wire.getMessage();
                            throw new EngineWireException(
                                    wire.code(), msg == null || msg.isBlank() ? "Build is already running" : msg);
                        }
                        throw new EngineWireException(wire.code(), "jk engine: build failed: " + wire.getMessage());
                    }
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
            }
            throw disconnectFailure();
        } finally {
            // The job is over however the stream ended.
            if (notedJid > 0) cc.jumpkick.cli.engine.EngineClient.ActiveJobs.forget(notedJid);
        }
    }

    /** Dispatch a wire error line to the plan listener (enriched test-failure when fields present). */
    private static void dispatchError(BuildPlanListener listener, String line) {
        if (listener == null) return;
        TestFailureInfo failure = testFailureFromWire(line);
        String task = Jsonl.str(line, "task");
        String code = Jsonl.str(line, "code");
        String message = Jsonl.str(line, "message");
        if (failure != null) {
            listener.error(task, code, message, failure);
        } else {
            listener.error(task, code, message, Jsonl.str(line, "test"), Jsonl.str(line, "exceptionClass"));
        }
    }

    private static BuildPlanResult.Diagnostic diagnosticFromWire(String line) {
        TestFailureInfo f = testFailureFromWire(line);
        if (f != null) {
            return new BuildPlanResult.Diagnostic(
                    Jsonl.str(line, "task"), Jsonl.str(line, "code"), Jsonl.str(line, "message"), f);
        }
        return new BuildPlanResult.Diagnostic(
                Jsonl.str(line, "task"),
                Jsonl.str(line, "code"),
                Jsonl.str(line, "message"),
                Jsonl.str(line, "test"),
                Jsonl.str(line, "exceptionClass"));
    }

    /**
     * Parse enriched test-failure fields from an error/diagnostic wire line. Returns null when no
     * structured test identity is present (plain javac/resolve errors).
     */
    private static TestFailureInfo testFailureFromWire(String line) {
        String module = nz(Jsonl.topStr(line, "module"));
        String engine = nz(Jsonl.topStr(line, "engine"));
        String className = nz(Jsonl.topStr(line, "testClass"));
        if (className.isEmpty()) className = nz(Jsonl.topStr(line, "class"));
        String method = nz(Jsonl.topStr(line, "method"));
        if (method.isEmpty()) method = nz(Jsonl.topStr(line, "test"));
        String exceptionClass = nz(Jsonl.str(line, "exceptionClass"));
        String stack = nz(Jsonl.str(line, "stack"));
        if (stack.isEmpty()) {
            String th = Jsonl.nested(line, "throwable");
            if (th != null) stack = nz(Jsonl.str(th, "stack"));
        }
        String file = nz(Jsonl.str(line, "file"));
        int lineNo = Jsonl.intValue(line, "line", 0);
        int snippetStart = Jsonl.intValue(line, "snippetStart", 0);
        List<String> snippet = Jsonl.strArray(line, "snippet");
        if (module.isEmpty()
                && engine.isEmpty()
                && className.isEmpty()
                && method.isEmpty()
                && stack.isEmpty()
                && file.isEmpty()
                && !"test-failure".equals(Jsonl.str(line, "code"))) {
            return null;
        }
        if (module.isEmpty()
                && engine.isEmpty()
                && className.isEmpty()
                && method.isEmpty()
                && stack.isEmpty()
                && exceptionClass.isEmpty()
                && file.isEmpty()) {
            return null;
        }
        int worker = Jsonl.intValue(line, "worker", 0);
        if (worker <= 0) worker = Jsonl.intValue(line, "w", 0);
        return new TestFailureInfo(
                module,
                engine,
                className,
                method,
                exceptionClass,
                nz(Jsonl.str(line, "message")),
                stack,
                worker,
                file,
                lineNo,
                snippetStart,
                snippet);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<ModulePlan> buildModulePlans(Map<String, ModuleMeta> planByDir, Path cache) {
        List<ModulePlan> plans = new ArrayList<>(planByDir.size());
        for (Map.Entry<String, ModuleMeta> e : planByDir.entrySet()) {
            plans.add(buildModulePlan(e.getKey(), e.getValue(), cache));
        }
        return plans;
    }

    private static ModulePlan buildModulePlan(String dir, ModuleMeta m, Path cache) {
        BuildPlan inertBuildPlan =
                BuildPlan.builder(m.planName).addAllTasks(m.steps).build();
        return ModulePlan.fromWire(Path.of(dir), m.coord, inertBuildPlan, m.weight, m.fullyCached, cache);
    }

    private static BuildPlanView readBuildPlanView(String line) {
        return new BuildPlanView(
                Jsonl.str(line, "planName"),
                Jsonl.longValue(line, "numerator", 0),
                Jsonl.longValue(line, "denominator", 0),
                Jsonl.intValue(line, "tasksTotal", 0),
                Jsonl.intValue(line, "tasksComplete", 0),
                Jsonl.bool(line, "cancelled", false));
    }

    private static final BuildPlanListener NOOP = new BuildPlanListener() {};

    private static String wireGroup(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
