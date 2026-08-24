// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.PublishPlans;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

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
        return ProtoSession.withTrigger(
                ProtoJobs.publishRequest(
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
                        false),
                "web");
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String jar = Jsonl.str(requestLine, "jar");
                String keyFile = Jsonl.str(requestLine, "keyFile");
                RepoCredential credential =
                        switch (String.valueOf(Jsonl.str(requestLine, "authType"))) {
                            case "basic" ->
                                new RepoCredential.Basic(
                                        Jsonl.str(requestLine, "user"),
                                        Jsonl.str(requestLine, "pass") != null ? Jsonl.str(requestLine, "pass") : "");
                            case "bearer" -> new RepoCredential.Bearer(Jsonl.str(requestLine, "token"));
                            default -> RepoCredential.ANONYMOUS;
                        };
                PublishPlans.Request req = new PublishPlans.Request(
                        URI.create(Jsonl.str(requestLine, "repoUrl")),
                        Jsonl.str(requestLine, "region"),
                        Jsonl.str(requestLine, "endpoint"),
                        jar != null ? Path.of(jar) : null,
                        Jsonl.bool(requestLine, "allowSnapshot", false),
                        Jsonl.bool(requestLine, "dryRun", false),
                        keyFile != null ? Path.of(keyFile) : null,
                        Jsonl.str(requestLine, "gpgPassphrase"),
                        Jsonl.bool(requestLine, "sigstore", false),
                        Jsonl.bool(requestLine, "slsa", false),
                        Jsonl.bool(requestLine, "sbom", false),
                        credential);
                Session session = Session.defaults()
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                BuildPlan plan = PublishPlans.publishBuildPlan(entryDir, cache, req);
                host.streamSinglePlan(
                        plan,
                        session,
                        writer,
                        result -> ProtoEvents.planFinishPublish(
                                dir,
                                result.success(),
                                plan.get(PublishPlans.FILES).orElse(-1)));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
