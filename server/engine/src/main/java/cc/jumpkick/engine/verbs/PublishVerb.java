// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.Session;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.base.PublishPlans;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.PublishRequest;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class PublishVerb implements HostedVerb {

    private final VerbHost host;

    public PublishVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.PUBLISH_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("publish");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-publish-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("publish");
    }

    /**
     * Detached publish is always a DRY RUN: credentials are resolved client-side by design
     * ({@code PublishCommand} — env/keychain reads never happen inside the engine), so the seam
     * can validate the bundle but never upload. The placeholder repo URL only rides the plan
     * config; the worker skips the network on {@code dryRun}.
     */
    @Override
    public String decodeJob(JobSpec spec) {
        return new PublishRequest(
                        spec.dir(),
                        JkDirs.cache().toString(),
                        "https://publish.invalid/",
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
                        "anonymous",
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        null)
                .encode();
    }

    /** The publishing type as the CLI spells it: {@code user-managed} unless the request said {@code automatic}. */
    private static String publishingType(PublishPlans.Request req) {
        String type = req.publishingType();
        return type == null || type.isBlank()
                ? "user-managed"
                : type.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                PublishRequest body = PublishRequest.decode(requestLine);
                Path entryDir = Path.of(body.dir());
                Path cache = Path.of(body.cache());
                RepoCredential credential =
                        switch (String.valueOf(body.authType())) {
                            case "basic" ->
                                new RepoCredential.Basic(
                                        Objects.requireNonNull(body.user(), "user"),
                                        body.pass() != null ? body.pass() : "");
                            case "bearer" -> new RepoCredential.Bearer(Objects.requireNonNull(body.token(), "token"));
                            default -> RepoCredential.ANONYMOUS;
                        };
                // The one credential the engine does not resolve: the CLI resolved it (env,
                // keychain and settings.xml are read client-side by design) and shipped it over
                // the socket. RepoCredentialResolver files every value it resolves, so this decode
                // is the only path by which a live credential enters the engine unfiled — and a
                // publish is exactly where a transport error quotes a 401 body or a header back at
                // the user. Filed against the request's directory rather than the ambient session:
                // the session below does not exist yet.
                ResolvedSecrets.recordFor(entryDir, credential.secret());
                PublishPlans.Request req = new PublishPlans.Request(
                        body.repoUrl() == null ? null : URI.create(body.repoUrl()),
                        body.region(),
                        body.endpoint(),
                        body.jar() != null ? Path.of(body.jar()) : null,
                        body.allowSnapshot(),
                        body.dryRun(),
                        body.keyFile() != null ? Path.of(body.keyFile()) : null,
                        body.gpgPassphrase(),
                        body.sigstore(),
                        body.slsa(),
                        body.sbom(),
                        credential,
                        body.central(),
                        body.publishingType());
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                BuildPlan plan = PublishPlans.publishBuildPlan(entryDir, cache, req);
                long rid = host.currentRequestId();
                return host.streamSinglePlan(plan, session, writer, result -> {
                    PublishPlans.Deployment deployment =
                            plan.get(PublishPlans.DEPLOYMENT).orElse(null);
                    List<String> bundle = plan.get(PublishPlans.BUNDLE).orElse(List.of());
                    int files = plan.get(PublishPlans.FILES).orElse(-1);
                    // The results file gets the same facts the wire carries: where the run sent
                    // what, and the Portal's verdict with every error it listed.
                    host.accPublish(
                            rid,
                            new BuildRecord.Publish(
                                    req.central()
                                            ? "Central Portal (" + publishingType(req) + ")"
                                            : String.valueOf(req.repoUrl()),
                                    Math.max(files, 0),
                                    req.dryRun(),
                                    deployment == null ? null : deployment.id(),
                                    deployment == null ? null : deployment.state(),
                                    deployment == null ? List.of() : deployment.errors(),
                                    bundle));
                    return ProtoEvents.planFinishPublish(
                            dir,
                            result.success(),
                            files,
                            plan.get(PublishPlans.WRITTEN).orElse(List.of()),
                            deployment == null ? null : deployment.id(),
                            deployment == null ? null : deployment.state(),
                            deployment == null ? List.of() : deployment.errors(),
                            bundle);
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
