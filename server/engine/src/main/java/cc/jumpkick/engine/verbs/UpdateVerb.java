// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code update-request}: re-resolve cascade or {@code --git} splice. */
public final class UpdateVerb implements HostedVerb {

    private final VerbHost host;

    public UpdateVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.UPDATE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("update");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-update-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("update");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        return ProtoSession.withTrigger(
                ProtoJobs.updateRequest(
                        spec.dir(),
                        JkDirs.cache().toString(),
                        List.of(),
                        false,
                        null,
                        false,
                        null,
                        false,
                        false,
                        false,
                        null),
                "web");
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            List<String> features = Jsonl.strArray(requestLine, "features");
            boolean withDefaults = !Jsonl.bool(requestLine, "noDefaultFeatures", false);
            boolean gitOnly = Jsonl.bool(requestLine, "gitOnly", false);
            String gitTarget = Jsonl.str(requestLine, "gitTarget");
            Session session = host.resolveSession(requestLine, cancelToken, false);
            URI repoUrl = LockVerb.repoUrlOf(requestLine);
            String platformOverride = Jsonl.str(requestLine, "platform");
            if (platformOverride != null && platformOverride.isBlank()) platformOverride = null;
            String platformFinal = platformOverride;
            SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                if (gitOnly) {
                    Files.createDirectories(cache);
                    JkBuild root;
                    try {
                        root = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                    } catch (RuntimeException e) {
                        host.sendQuiet(
                                writer,
                                ProtoEvents.lockFinish(
                                        false, Exit.CONFIG, List.of(cc.jumpkick.host.Errors.text(e)), -1));
                        return null;
                    }
                    var outcome =
                            LockPlans.updateGitOnly(entryDir, root, cache, repoUrl, features, withDefaults, gitTarget);
                    host.sendQuiet(
                            writer,
                            ProtoEvents.lockFinish(
                                    outcome.exitCode() == 0,
                                    outcome.exitCode(),
                                    outcome.error() != null ? List.of(outcome.error()) : List.of(),
                                    outcome.refreshed()));
                } else {
                    LockCascade.run(
                            host, entryDir, cache, repoUrl, features, withDefaults, false, true, platformFinal, writer);
                }
                return null;
            });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
