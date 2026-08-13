// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;

public final class CompileVerb implements HostedVerb {

    private final VerbHost host;

    public CompileVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.COMPILE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("compile");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-compile-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String profile = Jsonl.str(requestLine, "profile");
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                Session session = host.resolveSession(requestLine, cancelToken, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Constructed in-session — see runImage's note on ambient-session capture.
                cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                        session,
                        () -> cc.jumpkick.runtime.CompilePlans.compileBuildPlan(
                                session.workingDir(), session.cacheDir(), profile, verbose));
                host.streamSinglePlan(plan, session, writer, result -> ProtoEvents.planFinish(dir, result.success()));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
