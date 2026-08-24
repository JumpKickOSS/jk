// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.DenyReport;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.PolicyOps;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class DenyCheckVerb implements HostedVerb {

    private final VerbHost host;

    public DenyCheckVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.DENY_CHECK_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("deny-check");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-deny-";
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            DenyReport report;
            try {
                report = PolicyOps.denyCheck(Path.of(Jsonl.str(requestLine, "dir")));
            } catch (RuntimeException e) {
                report = DenyReport.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
