// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockDiff;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.AddedPackages;
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
import java.util.Map;
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
                if (body.preview()) {
                    return preview(
                            entryDir,
                            cache,
                            repoUrl,
                            body.features(),
                            !body.noDefaultFeatures(),
                            new LockMode.Update(platformFinal),
                            selection,
                            writer);
                }
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
                            body.accept(),
                            writer);
                }
                Files.createDirectories(cache);
                JkBuild root;
                try {
                    root = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                } catch (RuntimeException e) {
                    return LockCascade.fail(host, writer, Exit.CONFIG, Errors.text(e));
                }
                var outcome = LockPlans.updateGitOnly(
                        entryDir, root, cache, repoUrl, body.features(), !body.noDefaultFeatures(), body.gitTarget());
                if (outcome.exitCode() != Exit.SUCCESS && outcome.error() != null) {
                    return LockCascade.fail(host, writer, outcome.exitCode(), outcome.error());
                }
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
            long rid = host.eventRequestId();
            if (rid > 0) host.accRunErrors(rid, List.of(Errors.text(e)));
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }

    /**
     * The planned pin moves as {@code update-rewrite} events, each naming the packages its relock
     * would add to an existing lock; nothing is written or relocked. Those packages come from a
     * resolve that reads the moved manifests from memory, under the lock dir's gate.
     */
    private JobOutcome preview(
            Path entryDir,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            LockMode mode,
            ManifestUpdates.Selection selection,
            @Nullable BufferedWriter writer) {
        try {
            Path lockDir = LockPlans.lockScope(entryDir).lockDir();
            synchronized (LockGate.monitorFor(lockDir)) {
                ManifestUpdates.Plan plan = ManifestUpdates.plan(lockDir, repoUrl, selection);
                Map<ManifestUpdates.Rewrite, List<String>> adding = Map.of();
                Lockfile before = LockDiff.current(lockDir);
                if (before != null && !plan.isEmpty()) {
                    try {
                        adding = AddedPackages.byMove(
                                before,
                                AddedPackages.probe(
                                        lockDir, plan.contents(), cache, repoUrl, features, withDefaults, mode),
                                plan.rewrites());
                    } catch (Exception e) {
                        if (e instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                        // The update itself meets the same failure and reports it.
                        Log.debug("update: no probe of the packages the moves add", e);
                    }
                }
                for (ManifestUpdates.Rewrite r : plan.rewrites()) {
                    LockCascade.sendRewrite(host, writer, r, false, adding.getOrDefault(r, List.of()));
                }
            }
        } catch (RuntimeException | IOException e) {
            return LockCascade.fail(host, writer, Exit.CONFIG, Errors.text(e));
        }
        host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
        return JobOutcome.ok();
    }
}
