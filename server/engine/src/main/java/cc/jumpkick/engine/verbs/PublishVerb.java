// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;

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
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String jar = Jsonl.str(requestLine, "jar");
                String keyFile = Jsonl.str(requestLine, "keyFile");
                cc.jumpkick.credential.RepoCredential credential =
                        switch (String.valueOf(Jsonl.str(requestLine, "authType"))) {
                            case "basic" ->
                                new cc.jumpkick.credential.RepoCredential.Basic(
                                        Jsonl.str(requestLine, "user"),
                                        Jsonl.str(requestLine, "pass") != null ? Jsonl.str(requestLine, "pass") : "");
                            case "bearer" ->
                                new cc.jumpkick.credential.RepoCredential.Bearer(Jsonl.str(requestLine, "token"));
                            default -> cc.jumpkick.credential.RepoCredential.ANONYMOUS;
                        };
                cc.jumpkick.runtime.PublishPlans.Request req = new cc.jumpkick.runtime.PublishPlans.Request(
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
                cc.jumpkick.run.BuildPlan plan =
                        cc.jumpkick.runtime.PublishPlans.publishBuildPlan(entryDir, cache, req);
                host.streamSinglePlan(
                        plan,
                        session,
                        writer,
                        result -> ProtoEvents.planFinishPublish(
                                dir,
                                result.success(),
                                plan.get(cc.jumpkick.runtime.PublishPlans.FILES).orElse(-1)));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
