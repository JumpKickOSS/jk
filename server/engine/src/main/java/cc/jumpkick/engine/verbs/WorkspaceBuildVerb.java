// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobSelect;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Workspace {@code build-request}: CLI JSONL stream. */
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
        return List.of("build", "assemble", "test");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        Set<Path> dirty = null;
        if (!spec.modules().isEmpty()) {
            // Module selection needs the manifest; without a filter an unparseable jk.toml is
            // accepted here and fails as a job (202 + request-finish), never a bare 400.
            JkBuild entry;
            try {
                entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
            } catch (Exception e) {
                throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
            }
            dirty = JobSelect.dirtyHint(entryDir, entry, spec.modules());
        }
        boolean testOnly = "test".equals(spec.kind());
        boolean skipTests = spec.skipTests() || "assemble".equals(spec.kind());
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
                                testOnly,
                                dirty == null
                                        ? null
                                        : dirty.stream()
                                                .map(Path::toString)
                                                .sorted()
                                                .toList(),
                                JobSelect.testSelection(spec.includeTags(), spec.excludeTags(), spec.suites()),
                                List.of(),
                                false,
                                null,
                                Map.of(),
                                null,
                                "web",
                                null)
                        .encode(),
                spec.affected() ? "\"affected\":true" : "");
    }

    /**
     * Non-positive wire concurrency resolves to the same effective jobs the CLI sends, so every
     * client takes the streaming scheduler — never the batch-per-level path.
     */
    static int effectiveModuleConcurrency(int wire) {
        if (wire > 0) return wire;
        return Jobs.resolve(JkEngineConfig.resolve());
    }

    /** A workspace test job journals as kind {@code test} on every surface, not {@code build}. */
    @Override
    public JobRequest toJobRequest(String requestLine) {
        boolean testOnly = BuildRequest.decode(requestLine).testOnly();
        return new JobRequest(JobKind.workspace(testOnly ? "test" : "build"), threadPrefix(), this::run);
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            BuildRequest body = BuildRequest.decode(requestLine);
            String entryDirStr = Objects.requireNonNull(body.dir(), "dir");
            String cacheStr = body.cache();
            String jdksDirStr = body.jdksDir();
            int workers = body.workers();
            String profile = body.profile();
            boolean skipTests = body.skipTests();
            boolean verbose = body.verbose();
            // One behavior for every client: absent/zero module concurrency resolves
            // to the same effective jobs the CLI sends (streaming scheduler — never the
            // batch-per-level path), and cross-module tests default parallel. Explicit wire
            // values (any client, any age) still win.
            int maxModuleConcurrency = effectiveModuleConcurrency(body.maxModuleConcurrency());
            boolean parallelTests = body.parallelTests();
            boolean offline = body.offline();
            boolean force = body.force();
            boolean rerun = Jsonl.bool(requestLine, "rebuild", false);
            boolean freshenLock = body.freshenLock();
            boolean ephemeralActions = body.ephemeralActions();
            boolean testOnly = body.testOnly();
            // Absent = fail-fast. The client resolves --continue / [engine] continue / CI and
            // sends the answer; the engine does not re-derive it from its own environment, which
            // is the daemon's and not the caller's.
            boolean keepGoing = body.keepGoing();
            List<String> dirtyHintDirs = body.dirtyHint();

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;

            List<String> moduleTokens = body.modules();
            Set<Path> dirty = dirtyHintDirs == null
                    ? null
                    : dirtyHintDirs.stream().map(Path::of).collect(Collectors.toUnmodifiableSet());
            if (dirty == null && !moduleTokens.isEmpty()) {
                JkBuild entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
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
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            cache,
                            jdksDir,
                            workers,
                            profile,
                            skipTests,
                            verbose,
                            maxModuleConcurrency,
                            dirty,
                            false,
                            freshenLock)
                    .withModules(moduleTokens)
                    .withKeepGoing(keepGoing)
                    .withTestOnly(testOnly)
                    .withEphemeralActions(ephemeralActions)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine));
            String workspaceTarget = body.workspaceTarget();
            // The client resolved a GraalVM home for every module whose build links a native
            // image; the plan reads it from the spec for any target, or the daemon would pick one
            // from the shell that started it.
            Map<Path, Path> graalByDir = new LinkedHashMap<>();
            Map<String, String> homes = body.graalHomes();
            if (homes != null) {
                homes.forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
            }
            if ("install".equals(workspaceTarget)) {
                Set<Path> selected = dirty == null ? Set.of() : dirty;
                // The m2 root is the client's answer for the same reason the Graal homes are:
                // rebuilding the spec with null here sent every workspace install to the daemon's
                // own ~/.m2, so `--m2-dir` was honored for a lone module and silently dropped for
                // a workspace — the redirect a test asks for landing in the real home repo.
                Path m2 = body.m2Dir() == null || body.m2Dir().isBlank() ? null : Path.of(body.m2Dir());
                req = req.withSpec(WorkspaceSpec.install(selected, graalByDir, m2));
            } else if (!graalByDir.isEmpty()) {
                WorkspaceSpec spec = req.spec() == null ? WorkspaceSpec.DEFAULT : req.spec();
                req = req.withSpec(spec.withGraalByDir(graalByDir));
            }
            WorkspaceRequest workspaceReq = req;

            JkConfig config = JkConfig.empty()
                    .withOffline(offline)
                    .withRebuild(rerun)
                    .withVerbose(verbose)
                    .withForce(force);
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withParallelTests(parallelTests)
                    .withTestSelection(body.selection())
                    .withAffected(Jsonl.bool(requestLine, "affected", false))
                    .withCancel(cancelToken)
                    .withJvm(ProtoSession.jvmTuning(requestLine))
                    // The request's env belongs on the session too, not only on the request: it is
                    // what BuildEnv hands every build-path caller, and without it `FOO=x jk build`
                    // reached variant `env:` indirection (which is passed the request's map
                    // directly) but nothing that asked BuildEnv — so `[test] env` resolved against
                    // the daemon's own environment instead of the caller's.
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    // The request's toolchain selection belongs on it too: without this the SWITCH tier is
                    // empty and a resident engine ignores both --jdk and JK_JDK.
                    .withToolchainSpecs(
                            ProtoSession.jdkSpecOf(requestLine),
                            ProtoSession.graalSpecOf(requestLine),
                            ProtoSession.graalHomeOf(requestLine));

            long rid = host.eventRequestId();
            if (rid > 0) host.putProgressRoot(rid, entryDirStr);
            WorkspaceBuildListener listener = host.workspaceListener(writer, entryDirStr);
            WorkspaceResult result =
                    SessionContext.where(session, () -> BuildService.buildWorkspace(workspaceReq, listener));
            return WorkspaceTerminal.finish(host, writer, entryDirStr, result, cancelToken.cancelled());
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
            String msg = host.redactEnv(dir, Errors.text(e));
            host.sendQuiet(writer, host.requestFailedLine(dir, e));
            host.publishRequestError(rid, dir, msg);
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
