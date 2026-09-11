// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.FormatStyles;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.base.FormatPlans;
import cc.jumpkick.runtime.base.FormatWorker;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.FormatRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class FormatVerb implements HostedVerb {

    private final VerbHost host;

    public FormatVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.FORMAT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("format");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-format-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("format");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        JkBuild entry;
        try {
            entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
        }
        // Same style/hygiene precedence as `jk format` without CLI flags: the entry [format]
        // table, then the built-in defaults — one verb, one result across entry points.
        FormatStyles.Resolved styles = FormatStyles.resolve(null, null, null, null, null, null, entry.format());
        return ProtoSession.withTrigger(
                new FormatRequest(
                                spec.dir(),
                                JkDirs.cache().toString(),
                                false,
                                styles.java(),
                                styles.kotlin(),
                                styles.optimizeImports(),
                                styles.importOrder(),
                                styles.removeUnusedImports(),
                                false,
                                false)
                        .encode(),
                "web");
    }

    /**
     * The job verdict for a format run, from the plan's verdict and the run's per-file error count.
     *
     * <p>Two different questions, and only one of them is the plan's. "Did the run reach the end" is
     * {@code BuildPlanResult.success()}, which {@link FormatWorker#reconcile} fails for a worker that
     * died. "Did the run do its job" also needs the files: a run that visited all of them and could
     * not format one is not a green job, however completely it ran.
     *
     * <p>The count, not the worker's exit code: the worker exits {@code 1} for {@code --check} drift
     * too, and a drifted tree is a complete, clean <em>job</em> whose command exits non-zero.
     */
    static JobOutcome verdict(JobOutcome planVerdict, int errors) {
        return PlanBurst.withToolExit(planVerdict, errors > 0 ? Exit.FAILURE : 0);
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                FormatRequest body = FormatRequest.decode(requestLine);
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                BuildPlan plan = FormatPlans.formatBuildPlan(
                        session.workingDir(),
                        session.cacheDir(),
                        body.check(),
                        body.javaStyle(),
                        body.kotlinStyle(),
                        body.optimizeImports(),
                        body.importOrder(),
                        body.removeUnusedImports(),
                        (path, status, message, index, total) -> host.sendQuiet(
                                writer, ProtoEvents.formatFile(dir, path, status, message, index, total)));
                // `result.success()` answers one question: did the format reach the end? It is false
                // when the worker died mid-run (FormatWorker.reconcile), so a partial format is
                // never reported as a complete one. That is what the event carries and what the CLI
                // reads it as. The changed/clean/errors tallies stay off the wire: the CLI tallies
                // all five summary categories from the per-file format-file stream.
                JobOutcome planVerdict = host.streamSinglePlan(
                        plan,
                        session,
                        writer,
                        result -> ProtoEvents.planFinishFormat(
                                dir,
                                result.success(),
                                plan.get(FormatWorker.TOTAL).orElse(-1),
                                plan.get(FormatWorker.WORKER_EXIT).orElse(-1)));
                return verdict(planVerdict, plan.get(FormatWorker.ERRORS).orElse(0));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
