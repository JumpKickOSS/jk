// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.test.AffectedTests;
import cc.jumpkick.test.AffectedTestsCompute;
import cc.jumpkick.test.JkTestsAffectedMarkdown;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.protocol.AffectedTestsRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** {@code jk test --affected} / {@code --affected-since} — ranked list, no test run. */
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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            AffectedTestsReport report;
            try {
                AffectedTestsRequest req = AffectedTestsRequest.decode(requestLine);
                Path dir = Path.of(req.dir());
                String since = req.affectedSince();
                Set<Path> only = null;
                String modules = req.modules();
                if (modules != null && !modules.isBlank()) {
                    // -m intersects the ranked cone, exactly as it does the build cone.
                    JkBuild entry = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
                    ModuleSelection.Result sel = ModuleSelection.resolve(dir, entry, modules);
                    if (!sel.ok()) {
                        host.sendQuiet(
                                writer,
                                AffectedTestsReport.error("bad-selection", sel.errorMessage())
                                        .encode());
                        return JobOutcome.declined();
                    }
                    only = new LinkedHashSet<>();
                    for (Path m : sel.moduleDirs()) only.add(m.toAbsolutePath().normalize());
                }
                AffectedTests ranked = AffectedTestsCompute.fromDisk(dir, req.selection(), only, since);
                JkTestsAffectedMarkdown.write(JkTestsAffectedMarkdown.latestPath(dir), ranked);
                report = toReport(ranked);
            } catch (Exception e) {
                report = AffectedTestsReport.error("internal", Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    static AffectedTestsReport toReport(AffectedTests ranked) {
        AffectedTests.Refuse refuse = ranked.refuse();
        if (refuse != null) {
            return AffectedTestsReport.error(refuse.code(), refuse.message());
        }
        List<AffectedTestsReport.Row> rows = new ArrayList<>();
        for (AffectedTests.Row r : ranked.ranked()) {
            rows.add(new AffectedTestsReport.Row(r.score(), r.className(), r.reason()));
        }
        return AffectedTestsReport.of(ranked.cap(), ranked.candidateCount(), rows);
    }
}
