// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.LockMode;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.UpdateRequest;
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
                new UpdateRequest(
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
                                null)
                        .encode(),
                "web");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            UpdateRequest body = UpdateRequest.decode(requestLine);
            Session session = host.resolveSession(requestLine, cancelToken, false);
            URI repoUrl = body.repoUrl() == null ? null : URI.create(body.repoUrl());
            String platformOverride = body.platform();
            if (platformOverride != null && platformOverride.isBlank()) platformOverride = null;
            String platformFinal = platformOverride;
            return SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                if (!body.gitOnly()) {
                    return LockCascade.run(
                            host,
                            entryDir,
                            cache,
                            repoUrl,
                            body.features(),
                            !body.noDefaultFeatures(),
                            new LockMode.Update(platformFinal),
                            false,
                            writer);
                }
                Files.createDirectories(cache);
                JkBuild root;
                try {
                    root = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                } catch (RuntimeException e) {
                    host.sendQuiet(writer, ProtoEvents.lockFinish(false, Exit.CONFIG, List.of(Errors.text(e)), -1));
                    return JobOutcome.failed(Exit.CONFIG);
                }
                var outcome = LockPlans.updateGitOnly(
                        entryDir, root, cache, repoUrl, body.features(), !body.noDefaultFeatures(), body.gitTarget());
                host.sendQuiet(
                        writer,
                        ProtoEvents.lockFinish(
                                outcome.exitCode() == 0,
                                outcome.exitCode(),
                                outcome.error() != null ? List.of(outcome.error()) : List.of(),
                                outcome.refreshed()));
                return outcome.exitCode() == Exit.SUCCESS ? JobOutcome.ok() : JobOutcome.failed(outcome.exitCode());
            });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
