// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.graph.GraphRunner;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobSelect;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.NativePreflight;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ShadowManifests;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Every build-like goal, for every client: a {@code build-request} names its {@link
 * WorkspaceTarget} and the tail's arguments, and runs through the one workspace orchestrator. A
 * standalone project is a workspace of one.
 */
public final class WorkspaceBuildVerb implements HostedVerb {

    private final VerbHost host;

    public WorkspaceBuildVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.BUILD_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.workspace("build");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-build-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("build", "assemble", "test", "guard", "compile", "image", "install", "native", "publish");
    }

    /**
     * A dashboard or agent job. Image and native jobs deliver an artifact and skip tests; a publish
     * job is always a dry run, because credentials are resolved client-side and never here.
     */
    @Override
    public String decodeJob(JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        String kind = spec.kind();
        boolean guard = "guard".equals(kind);
        WorkspaceTarget target =
                switch (kind) {
                    case "test" -> WorkspaceTarget.TEST;
                    case "compile" -> WorkspaceTarget.COMPILE;
                    case "image" -> WorkspaceTarget.IMAGE;
                    case "install" -> WorkspaceTarget.INSTALL;
                    case "native" -> WorkspaceTarget.NATIVE;
                    case "publish" -> WorkspaceTarget.PUBLISH;
                    default -> WorkspaceTarget.PACKAGE;
                };
        Set<Path> selected = null;
        if (!spec.modules().isEmpty() || target == WorkspaceTarget.NATIVE) {
            // Module selection needs the manifest; without a filter an unparseable jk.toml is
            // accepted here and fails as a job (202 + request-finish), never a bare 400.
            JkBuild entry = parse(entryDir);
            selected = JobSelect.selected(entryDir, entry, spec.modules());
        }
        Map<String, String> graalHomes = target == WorkspaceTarget.NATIVE ? nativeHomes(entryDir, selected) : Map.of();
        TailArgs tail =
                switch (target) {
                    case IMAGE -> new TailArgs.Image(null, null, null, null, null);
                    case INSTALL -> new TailArgs.Install(null, null);
                    case NATIVE -> new TailArgs.Native(null, List.of());
                    case PUBLISH ->
                        new TailArgs.Publish(
                                URI.create("https://publish.invalid/"),
                                null,
                                null,
                                null,
                                false,
                                true,
                                null,
                                null,
                                false,
                                false,
                                false,
                                RepoCredential.ANONYMOUS,
                                false,
                                null);
                    default -> null;
                };
        boolean skipTests = spec.skipTests()
                || "assemble".equals(kind)
                || guard
                || target == WorkspaceTarget.IMAGE
                || target == WorkspaceTarget.NATIVE;
        // Raw tokens, resolved by run() exactly as the CLI's are: the selection, not its cone.
        List<String> modules = spec.modules();
        return Jsonl.append(
                new BuildRequest(
                                entryDir.toString(),
                                JkDirs.cache().toString(),
                                JkDirs.jdks().toString(),
                                0,
                                null,
                                skipTests,
                                false,
                                0,
                                // Parallel module tests: same default as the CLI.
                                true,
                                false,
                                false,
                                true,
                                false,
                                target == WorkspaceTarget.TEST,
                                null,
                                guard
                                        ? TestSelection.of(List.of(), false, List.of(), List.of(), false, true)
                                        : JobSelect.testSelection(
                                                spec.includeTags(), spec.excludeTags(), spec.suites()),
                                null,
                                modules,
                                false,
                                target == WorkspaceTarget.PACKAGE || target == WorkspaceTarget.TEST
                                        ? null
                                        : target.name().toLowerCase(Locale.ROOT),
                                graalHomes,
                                tail,
                                null, // the admission point stamps who asked
                                null)
                        .encode(),
                (spec.affected() ? "\"affected\":true" : "")
                        + (guard ? (spec.affected() ? "," : "") + "\"guard\":true" : ""));
    }

    private static JkBuild parse(Path entryDir) {
        try {
            return JkBuildParser.parse(ManifestPaths.manifestIn(entryDir));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
        }
    }

    /**
     * A native job's GraalVM home for every native-eligible module in the selection. Never "every
     * module": an unrequested multi-minute native-image of unrelated modules is worse than a refusal.
     */
    private static Map<String, String> nativeHomes(Path entryDir, @Nullable Set<Path> selected) {
        Path graal = SessionContext.current().graalHome();
        if (graal == null || !Files.isDirectory(graal)) {
            throw new IllegalArgumentException(
                    "native job: no GraalVM home available (install one with `jk jdk graal`)");
        }
        Map<Path, JkBuild> allModules;
        try {
            allModules = nativeScopes(entryDir, parse(entryDir));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot load workspace modules: " + e.getMessage());
        }
        Set<Path> targets = nativeEligibleTargets(allModules, selected);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("native job: no native-eligible module in the selection "
                    + "(needs a [native] table or a unique main class)");
        }
        Map<String, String> homes = new LinkedHashMap<>();
        for (Path d : targets) homes.put(d.toString(), graal.toString());
        return homes;
    }

    private static Map<Path, JkBuild> nativeScopes(Path entryDir, JkBuild entry) throws IOException {
        if (!entry.isWorkspaceRoot()) return Map.of(entryDir, entry);
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(entryDir, entry);
        Map<Path, JkBuild> ordered = new LinkedHashMap<>();
        for (Path dir : BuildGraph.orderModules(entryDir, modules)) ordered.put(dir, modules.get(dir));
        return ordered;
    }

    /**
     * Selection ∩ native eligibility: modules with a {@code [native]} table, or, when no module
     * declares one, modules with a unique main. {@code enabled = false} never re-enters.
     */
    static Set<Path> nativeEligibleTargets(Map<Path, JkBuild> allModules, @Nullable Set<Path> selected) {
        boolean anyNativeTable = allModules.values().stream().anyMatch(JkBuild::nativeImage);
        Set<Path> targets = new LinkedHashSet<>();
        for (var e : allModules.entrySet()) {
            Path dir = e.getKey();
            if (selected != null && !selected.contains(BuildGraph.canonicalPath(dir))) continue;
            if (e.getValue().nativeExplicitlyDisabled()) continue;
            boolean eligible = e.getValue().nativeImage()
                    || (!anyNativeTable
                            && NativePreflight.resolveMain(dir, null) instanceof NativePreflight.Main.Unique);
            if (eligible) targets.add(dir);
        }
        return targets;
    }

    /**
     * Non-positive wire concurrency resolves to the same effective jobs the CLI sends, so every
     * client takes the streaming scheduler — never the batch-per-level path.
     */
    static int effectiveModuleConcurrency(int wire) {
        if (wire > 0) return wire;
        return Jobs.resolve(JkEngineConfig.resolve());
    }

    /** A run journals as its goal's kind on every surface: {@code test}, {@code native}, {@code guard}, … */
    @Override
    public JobRequest toJobRequest(String requestLine) {
        BuildRequest body = BuildRequest.decode(requestLine);
        boolean guard = Jsonl.bool(requestLine, "guard", false);
        return new JobRequest(
                JobKind.workspace(guard ? BuildHistoryKinds.GUARD : target(body).jobKind()), threadPrefix(), this::run);
    }

    private static WorkspaceTarget target(BuildRequest body) {
        String named = body.workspaceTarget();
        if (named != null) return WorkspaceTarget.valueOf(named.toUpperCase(Locale.ROOT));
        return body.testOnly() ? WorkspaceTarget.TEST : WorkspaceTarget.PACKAGE;
    }

    /** True for a goal whose tail applies to the modules the request selects, not to every module. */
    private static boolean selectsByModule(WorkspaceTarget target) {
        return switch (target) {
            case COMPILE, NATIVE, IMAGE, INSTALL, RESHELVE, TRAIN, PUBLISH -> true;
            case PACKAGE, TEST, RUN -> false;
        };
    }

    /**
     * The one credential the engine does not resolve: a publish's, resolved by the CLI and shipped
     * over the socket. Filed against the request's directory before anything runs, so an error that
     * quotes it back is masked like every resolved secret, and only in that workspace's output.
     */
    static void fileShippedCredential(Path entryDir, @Nullable TailArgs tail) {
        if (tail instanceof TailArgs.Publish publish)
            ResolvedSecrets.recordFor(entryDir, publish.credential().secret());
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            BuildRequest body = BuildRequest.decode(requestLine);
            String entryDirStr = Objects.requireNonNull(body.dir(), "dir");
            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(body.cache());
            Path jdksDir = body.jdksDir() != null ? Path.of(body.jdksDir()) : null;
            WorkspaceTarget target = target(body);
            fileShippedCredential(entryDir, body.tail());

            List<String> moduleTokens = body.modules();
            Set<Path> dirty = body.dirtyHint() == null
                    ? null
                    : body.dirtyHint().stream().map(Path::of).collect(Collectors.toUnmodifiableSet());
            if (dirty == null && !moduleTokens.isEmpty()) {
                JkBuild entry = JkBuildParser.parse(ManifestPaths.manifestIn(entryDir));
                var hit = JobSelect.resolveTokens(entryDir, entry, moduleTokens);
                if (hit != null && !hit.ok()) {
                    host.sendQuiet(
                            writer, host.requestFailedLine(null, new IllegalArgumentException(hit.errorMessage())));
                    return JobOutcome.failed(Exit.CONFIG);
                }
                if (hit != null) {
                    dirty = hit.moduleDirs().stream()
                            .map(BuildGraph::canonicalPath)
                            .collect(Collectors.toUnmodifiableSet());
                }
            }
            // The client resolved a GraalVM home for every module whose build links a native
            // image; the plan reads it from the spec, or the daemon would pick one from the shell
            // that started it.
            Map<Path, Path> graalByDir = new LinkedHashMap<>();
            body.graalHomes().forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
            Set<Path> selected = !selectsByModule(target)
                    ? Set.of()
                    : dirty != null ? dirty : target == WorkspaceTarget.NATIVE ? graalByDir.keySet() : Set.of();
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            cache,
                            jdksDir,
                            body.workers(),
                            body.profile(),
                            body.skipTests(),
                            body.verbose(),
                            effectiveModuleConcurrency(body.maxModuleConcurrency()),
                            dirty,
                            false,
                            body.freshenLock())
                    .withModules(moduleTokens)
                    .withKeepGoing(body.keepGoing())
                    .withTestOnly(body.testOnly())
                    .withEphemeralActions(body.ephemeralActions())
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    .withSpec(new WorkspaceSpec(target, selected, graalByDir, body.tail()));

            Session session = ProtoSession.sessionOf(requestLine, cancelToken);
            long rid = host.eventRequestId();
            if (rid > 0) host.putProgressRoot(rid, entryDirStr);
            WorkspaceBuildListener listener = host.workspaceListener(writer, entryDirStr);
            WorkspaceResult result = SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            // The graph is a build product: one that exists is brought up to the tree the build
            // just compiled before the build is reported done.
            if (result.success() && !cancelToken.cancelled()) GraphRunner.refreshExisting(Path.of(entryDirStr));
            return WorkspaceTerminal.finish(host, writer, entryDirStr, result, cancelToken.cancelled());
        } catch (ShadowManifests.NotBuiltHere refused) {
            // A shadowed directory Maven would not build here: a configuration refusal, not a crash.
            host.sendQuiet(writer, host.requestFailedLine(Jsonl.str(requestLine, "dir"), refused));
            return JobOutcome.failed(Exit.CONFIG);
        } catch (Exception e) {
            String dir = Jsonl.str(requestLine, "dir");
            long rid = host.eventRequestId();
            boolean cancelled = host.effectiveCancelled(rid, cancelToken.cancelled());
            if (cancelled) {
                // Declined, not cancelled: the cancel stamps on the accumulator already carry
                // which kind of cancel this was, and a verdict here would overrule them.
                host.sendQuiet(writer, ProtoEvents.workspaceFinish(false, Exit.FAILURE, List.of(), true));
                return JobOutcome.declined();
            }
            String msg = EventRedaction.redactText(dir, Errors.text(e));
            host.sendQuiet(writer, host.requestFailedLine(dir, e));
            host.publishRequestError(rid, dir, msg);
            // The run's record names why, so `jk history` and the agent report do too.
            if (rid > 0) host.accRunErrors(rid, List.of(msg));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
