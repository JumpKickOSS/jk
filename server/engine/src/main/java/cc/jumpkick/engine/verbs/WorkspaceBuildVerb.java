// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            int workers = Jsonl.intValue(requestLine, "workers", 0);
            String profile = Jsonl.str(requestLine, "profile");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            int maxModuleConcurrency = Jsonl.intValue(requestLine, "maxModuleConcurrency", 0);
            boolean parallelTests = Jsonl.bool(requestLine, "parallelTests", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean rerun = Jsonl.bool(requestLine, "rebuild", false);
            boolean freshenLock = Jsonl.bool(requestLine, "freshenLock", false);
            boolean ephemeralActions = Jsonl.bool(requestLine, "ephemeralActions", false);
            boolean testOnly = Jsonl.bool(requestLine, "testOnly", false);
            List<String> dirtyHintDirs = EngineProtocol.dirtyHintOf(requestLine);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;

            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            entryBuild,
                            cache,
                            jdksDir,
                            workers,
                            profile,
                            skipTests,
                            verbose,
                            maxModuleConcurrency,
                            dirtyHintDirs == null
                                    ? null
                                    : dirtyHintDirs.stream().map(Path::of).collect(Collectors.toUnmodifiableSet()),
                            false,
                            freshenLock)
                    .withTestOnly(testOnly)
                    .withEphemeralActions(ephemeralActions)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(rerun),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withParallelTests(parallelTests)
                    .withTestSelection(EngineProtocol.testSelectionOf(requestLine))
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));

            long rid = host.eventRequestId();
            if (rid > 0) host.putProgressRoot(rid, entryDirStr);
            WorkspaceBuildListener listener = host.workspaceListener(writer, entryDirStr);
            WorkspaceResult result = SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            host.releaseExclusiveSlot();
            boolean cancelled = result.cancelled() || host.effectiveCancelled(rid, cancelToken.cancelled());
            host.accOutcome(rid, result.success() && !cancelled, result.exitCode());
            if (rid > 0) {
                if (result.success() && !cancelled) host.finishProgress(rid);
                host.emitWorkspaceProgress(rid, writer, true);
            }
            host.flushTimeline(rid, writer);
            List<String> safeErrors = result.errors().stream()
                    .map(err -> host.redactEnv(entryDirStr, err))
                    .toList();
            host.send(
                    writer,
                    EngineProtocol.workspaceFinish(
                            result.success() && !cancelled, result.exitCode(), safeErrors, cancelled));
            if (!result.success() && !cancelled) {
                for (String error : safeErrors.stream().limit(5).toList()) {
                    host.publishRequestError(host.eventRequestId(), entryDirStr, error);
                }
            }
        } catch (Exception e) {
            String dir = Jsonl.str(requestLine, "dir");
            long rid = host.eventRequestId();
            boolean cancelled = host.effectiveCancelled(rid, cancelToken.cancelled());
            if (cancelled) {
                host.sendQuiet(writer, EngineProtocol.workspaceFinish(false, 1, List.of(), true));
            } else {
                host.accOutcome(rid, false, 1);
                String msg = host.redactEnv(dir, String.valueOf(e.getMessage()));
                host.sendQuiet(writer, host.requestFailedLine(dir, e));
                host.publishRequestError(rid, dir, msg);
            }
        }
    }
}
