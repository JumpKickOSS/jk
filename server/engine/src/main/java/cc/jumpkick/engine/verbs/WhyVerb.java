// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.GraphOps;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class WhyVerb implements HostedVerb {

    private final VerbHost host;

    public WhyVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.WHY_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("why");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-why-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            WhyReport report;
            try {
                report = GraphOps.why(Path.of(Jsonl.str(requestLine, "dir")), Jsonl.str(requestLine, "query"));
            } catch (RuntimeException e) {
                report = WhyReport.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
