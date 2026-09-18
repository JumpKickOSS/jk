// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.runtime.base.GraphOps;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.WhyReport;
import cc.jumpkick.wire.protocol.WhyRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            WhyReport report;
            try {
                WhyRequest req = WhyRequest.decode(requestLine);
                report = GraphOps.why(
                        Path.of(req.dir()),
                        req.query(),
                        new FeatureSelection(req.features(), !req.noDefaultFeatures()));
            } catch (RuntimeException e) {
                report = WhyReport.error(Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
