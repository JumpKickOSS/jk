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
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.UpdateRequest;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code update-request}: move the declared exact pins, then the re-resolve cascade; {@code preview}
 * reports the moves without writing; {@code gitOnly} is the {@code --git} splice.
 */
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
        return new UpdateRequest(
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
                        "",
                        List.of(),
                        false,
                        false)
                .encode();
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            UpdateRequest body = UpdateRequest.decode(requestLine);
            Session session = ProtoSession.sessionOf(requestLine, cancelToken);
            URI repoUrl = body.repoUrl() == null ? null : URI.create(body.repoUrl());
            String platformOverride = body.platform();
            if (platformOverride != null && platformOverride.isBlank()) platformOverride = null;
            String platformFinal = platformOverride;
            return SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                ManifestUpdates.Selection selection = new ManifestUpdates.Selection(body.deps(), body.major());
                if (body.preview()) return preview(entryDir, repoUrl, selection, writer);
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
                            selection,
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

    /** The planned pin moves as {@code update-rewrite} events; nothing is written or relocked. */
    private JobOutcome preview(
            Path entryDir,
            @Nullable URI repoUrl,
            ManifestUpdates.Selection selection,
            @Nullable BufferedWriter writer) {
        try {
            Path lockDir = LockPlans.lockScope(entryDir).lockDir();
            LockCascade.sendRewrites(host, writer, ManifestUpdates.plan(lockDir, repoUrl, selection));
        } catch (RuntimeException | IOException e) {
            host.sendQuiet(writer, ProtoEvents.lockFinish(false, Exit.CONFIG, List.of(Errors.text(e)), -1));
            return JobOutcome.failed(Exit.CONFIG);
        }
        host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
        return JobOutcome.ok();
    }
}
