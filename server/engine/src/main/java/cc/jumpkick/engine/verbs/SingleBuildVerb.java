// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.ShadowManifests;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.runtime.workspace.ModuleInputProvenance;
import cc.jumpkick.runtime.workspace.ModulePipeline;
import cc.jumpkick.runtime.workspace.StandalonePlanTimings;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.SingleBuildRequest;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Single-project {@code single-build-request}. */
public final class SingleBuildVerb implements HostedVerb {

    private final VerbHost host;

    public SingleBuildVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.SINGLE_BUILD_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("build");
    }

    /** A guard run journals as kind {@code guard}, as a workspace one does. */
    @Override
    public JobRequest toJobRequest(String requestLine) {
        String verb = Jsonl.bool(requestLine, "guard", false) ? "guard" : "build";
        return new JobRequest(JobKind.plan(verb), threadPrefix(), this::run);
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-1build-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            SingleBuildRequest body = SingleBuildRequest.decode(requestLine);
            String entryDirStr = body.dir();
            String cacheStr = body.cache();
            String jdksDirStr = body.jdksDir();
            int workers = body.workers();
            String profile = body.profile();
            boolean skipTests = body.skipTests();
            boolean verbose = body.verbose();

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = ManifestPaths.manifestIn(entryDir);
            int workerCount = Math.max(0, workers);
            Session session = ProtoSession.sessionOf(requestLine, cancelToken);
            Path graal = body.graalHome() == null ? null : Path.of(body.graalHome());
            WorkspaceRequest request = new WorkspaceRequest(
                            entryDir, cache, jdksDir, workerCount, profile, skipTests, verbose, 0, null, false, false)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    .withSpec(WorkspaceSpec.DEFAULT.withGraalByDir(graal == null ? Map.of() : Map.of(entryDir, graal)));
            BuildPlan plan = SessionContext.where(session, () -> ModulePipeline.standalone(request, null));
            long barWeight = plan.estimatedTotalWeight();
            StandalonePlanTimings.attach(plan, entryDir, cache, Clock.SYSTEM);

            PlanBurst.announce(host, plan, writer);

            BuildGraph.Result preGraph = null;
            Map<Path, String> preFps = null;
            if (!session.config().rebuildOr(false) && !session.config().forceOr(false)) {
                try {
                    JkBuild entry = JkBuildParser.parse(buildFile);
                    BuildGraph.Result g = BuildGraph.resolve(entryDir, entry);
                    if (!g.hasErrors()) {
                        preGraph = g;
                        preFps =
                                PreflightMemo.snapshotFingerprints(g, skipTests).fingerprints();
                    }
                } catch (Exception e) {
                    // fail-open
                    Log.debug("run: fail-open", e);
                }
            }

            long startNanos = System.nanoTime();
            BuildPlanResult result = SessionContext.where(session, plan::run);
            host.releaseExclusiveSlot();
            host.accTests(
                    host.eventRequestId(), plan.get(BuildPlanner.TEST_RESULT).orElse(null));
            host.accAffected(
                    host.eventRequestId(), plan.get(BuildPlanner.AFFECTED_TESTS).orElse(null));
            JobOutcome outcome = result.success() ? JobOutcome.ok() : JobOutcome.failed(Exit.FAILURE);
            if (result.success() && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                // An up-to-date run's wall over the whole plan's weight is no throughput at all, and
                // would rewrite host-metrics.toml on every no-op to say so.
                if (moduleMs > 0 && BuildService.moduleDidWork(result)) {
                    Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                host.maybeEnqueuePrune(cache);
            }
            if (result.success() && preGraph != null && preFps != null) {
                PreflightMemo.storeDirty(entryDir, preGraph, skipTests, profile, Set.of(), preFps);
                PreflightMemo.storeGraph(entryDir, preGraph);
                ModuleInputProvenance.record(entryDir, preGraph, preFps);
            }
            return outcome;
        } catch (ShadowManifests.NotBuiltHere refused) {
            // A shadowed directory Maven would not build here: a configuration refusal, not a crash.
            host.sendQuiet(writer, host.requestFailedLine(null, refused));
            return JobOutcome.failed(Exit.CONFIG);
        } catch (Exception e) {
            // The build threw before it could rule. Declining here would hand the journal a run
            // with no failure rows, which derives green — a build that never finished, recorded
            // as a success.
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
