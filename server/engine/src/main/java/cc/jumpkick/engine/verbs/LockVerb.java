// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
        return new LockRequest(
                        spec.dir(),
                        JkDirs.cache().toString(),
                        List.of(),
                        false,
                        false,
                        null,
                        false,
                        false,
                        false,
                        false)
                .encode();
    }

    /**
     * Which semantics one request runs under. An invisible freshen keeps pins and stays soft
     * offline whatever else was asked for — {@code -F} on {@code jk tree} means "re-fetch", never
     * "rewrite my pins". Otherwise {@code -F} is what floats them; bare {@code jk lock} keeps them.
     */
    static LockMode modeFor(LockRequest body) {
        if (body.freshen()) return new LockMode.Freshen();
        return body.force() ? new LockMode.Latest(body.sources()) : new LockMode.Keep(body.sources());
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            LockRequest body = LockRequest.decode(requestLine);
            Session session = ProtoSession.sessionOf(requestLine, cancelToken);
            URI repoUrl = body.repoUrl() == null ? null : URI.create(body.repoUrl());
            return SessionContext.where(
                    session,
                    () -> LockCascade.run(
                            host,
                            session.workingDir(),
                            session.cacheDir(),
                            repoUrl,
                            body.features(),
                            !body.noDefaultFeatures(),
                            modeFor(body),
                            body.freshen(),
                            writer));
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
