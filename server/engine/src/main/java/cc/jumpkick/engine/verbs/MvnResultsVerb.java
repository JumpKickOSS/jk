// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.engine.maven.MavenEvents;
import cc.jumpkick.engine.maven.MavenRunReport;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.PomCoord;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.test.MarkdownTestReport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.MvnResultsRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code mvn-results-request}: journal a Maven run the client just finished. The body records
 * each reactor module, its mojo steps, its diagnostics and its tests; the envelope then writes the
 * same {@code jk-results.md} and history row a jk build gets.
 */
public final class MvnResultsVerb implements HostedVerb {

    /** The journal kind, and the tool the report names. */
    public static final String KIND = "mvn";

    private final VerbHost host;

    public MvnResultsVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.MVN_RESULTS_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan(KIND);
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-mvn-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            MvnResultsRequest body = MvnResultsRequest.decode(requestLine);
            if (body.dir() == null || body.events() == null) {
                host.sendQuiet(writer, host.requestFailedLine(null, "mvn-results-request needs dir and events"));
                return JobOutcome.failed(Exit.FAILURE);
            }
            Path projectDir = Path.of(body.dir());
            long rid = host.eventRequestId();
            // A POM that cannot name its project has no project to journal under: say which
            // element it lacks and write no row, rather than a row under build number 0.
            Optional<String> unnamed = PomCoord.missingIdentity(projectDir);
            if (unnamed.isPresent()) {
                host.discardJournal(rid);
                host.sendQuiet(writer, ProtoEvents.mvnResultsResult(null, unnamed.get()));
                return body.exit() == Exit.SUCCESS ? JobOutcome.ok() : JobOutcome.failed(body.exit());
            }
            MavenRunReport report = MavenRunReport.read(projectDir, Path.of(body.events()));
            // The row's headline is Maven's wall, not the few hundred milliseconds this job takes.
            host.accToolWall(rid, body.millis());
            for (MavenRunReport.Module m : report.modules()) record(rid, m);
            if (!report.isEmpty()) host.accTests(rid, report.tests());
            String results = report.isEmpty()
                    ? null
                    : projectDir
                            .resolve(BuildLayout.TARGET)
                            .resolve(JkResultsMarkdown.FILE_NAME)
                            .toString();
            host.sendQuiet(writer, ProtoEvents.mvnResultsResult(results, null));
            return body.exit() == Exit.SUCCESS ? JobOutcome.ok() : JobOutcome.failed(body.exit());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }

    private void record(long rid, MavenRunReport.Module m) {
        String dir = m.dir().toString();
        for (MavenEvents.Step s : m.steps()) host.accStepFinish(rid, dir, s.goal(), s.status(), s.millis());
        host.accBuildPlanFinish(
                rid,
                dir,
                new BuildPlanResult(
                        MavenRunReport.PLAN_NAME,
                        m.success(),
                        Duration.ofMillis(m.millis()),
                        List.of(),
                        List.of(),
                        m.errors(),
                        false,
                        false));
        host.accModule(
                rid,
                new ModuleOutcome(
                        m.coord(),
                        m.dir(),
                        m.success(),
                        m.success() ? Exit.SUCCESS : Exit.FAILURE,
                        m.millis(),
                        true,
                        false));
        if (!m.tests().isEmpty()) MarkdownTestReport.publish(dir, m.coord(), m.tests());
    }
}
