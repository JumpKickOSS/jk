// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.net.URI;
import java.util.List;

/** {@code lock-request}: one workspace (or standalone) lock plan. */
public final class LockVerb implements HostedVerb {

    private final VerbHost host;

    public LockVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.LOCK_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("lock");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-lock-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("lock");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        return ProtoSession.withTrigger(
                ProtoJobs.lockRequest(
                        spec.dir(),
                        JkDirs.cache().toString(),
                        List.of(),
                        false,
                        false,
                        null,
                        false,
                        false,
                        false,
                        false),
                "web");
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            List<String> features = Jsonl.strArray(requestLine, "features");
            boolean withDefaults = !Jsonl.bool(requestLine, "noDefaultFeatures", false);
            boolean sources = Jsonl.bool(requestLine, "sources", false);
            boolean conservative = Jsonl.bool(requestLine, "conservative", false);
            Session session = host.resolveSession(requestLine, cancelToken, false);
            URI repoUrl = repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                LockCascade.run(
                        host,
                        session.workingDir(),
                        session.cacheDir(),
                        repoUrl,
                        features,
                        withDefaults,
                        sources,
                        false,
                        null,
                        conservative,
                        writer);
                return null;
            });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }

    static @org.jspecify.annotations.Nullable URI repoUrlOf(String requestLine) {
        String s = Jsonl.str(requestLine, "repoUrl");
        return s != null ? URI.create(s) : null;
    }
}
