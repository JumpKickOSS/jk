// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.AffectedTestsReport;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.AffectedTests;
import cc.jumpkick.test.AffectedTestsCompute;
import cc.jumpkick.test.JkTestsAffectedMarkdown;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code jk test --affected} — ranked list, no test run. */
public final class AffectedTestsVerb implements HostedVerb {

    private final VerbHost host;

    public AffectedTestsVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.AFFECTED_TESTS_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("affected-tests");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-affected-tests-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            AffectedTestsReport report;
            try {
                Path dir = Path.of(Jsonl.str(requestLine, "dir"));
                AffectedTests ranked = AffectedTestsCompute.fromDisk(dir, ProtoJobs.testSelectionOf(requestLine), null);
                JkTestsAffectedMarkdown.write(JkTestsAffectedMarkdown.latestPath(dir), ranked);
                report = toReport(ranked);
            } catch (Exception e) {
                report = AffectedTestsReport.error("", Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    static AffectedTestsReport toReport(AffectedTests ranked) {
        if (ranked.refused()) {
            return AffectedTestsReport.error(
                    ranked.refuse().code(), ranked.refuse().message());
        }
        List<AffectedTestsReport.Row> rows = new ArrayList<>();
        for (AffectedTests.Row r : ranked.ranked()) {
            rows.add(new AffectedTestsReport.Row(r.score(), r.className(), r.reason()));
        }
        return AffectedTestsReport.of(ranked.cap(), ranked.candidateCount(), rows);
    }
}
