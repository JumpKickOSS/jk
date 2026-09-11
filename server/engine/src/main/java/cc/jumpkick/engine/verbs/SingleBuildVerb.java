// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.PlannerTails;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.TestSupport;
import cc.jumpkick.runtime.workspace.ModuleInputProvenance;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.SingleBuildRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
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
            Path buildFile = entryDir.resolve(ManifestPaths.MANIFEST);
            Path lockFile = LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            int estimatedTestCount = skipTests
                    ? 0
                    : TestSupport.estimateSelectedSuiteTestCount(
                            entryDir, ModuleLayout.isCompact(entryDir), body.selection());

            Session session = ProtoSession.sessionOf(requestLine, cancelToken);

            BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                            entryDir,
                            cache,
                            buildFile,
                            lockFile,
                            Objects.requireNonNull(lockFile.getParent(), "lock dir"),
                            workerCount,
                            estimatedTestCount,
                            profile,
                            jdksDir,
                            skipTests,
                            verbose,
                            /* testOnly */ false,
                            /* compileOnly */ false,
                            Set.of(),
                            session)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine));
            BuildPlan plan = SessionContext.where(session, () -> {
                BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs, false);
                Path graal = body.graalHome() == null ? null : Path.of(body.graalHome());
                PlannerTails.appendDeclaredTails(builder, inputs, graal);
                return builder.build();
            });
            long barWeight = plan.estimatedTotalWeight();

            PlanBurst.announce(host, plan, writer);

            BuildGraph.Result preGraph = null;
            Map<Path, String> preFps = null;
            if (!session.config().rebuildOr(false) && !session.config().forceOr(false)) {
                try {
                    JkBuild entry = JkBuildParser.parse(buildFile);
                    BuildGraph.Result g = BuildGraph.resolve(entryDir, entry);
                    if (!g.hasErrors()) {
                        preGraph = g;
                        preFps = PreflightMemo.snapshotFingerprints(g, skipTests);
                    }
                } catch (Exception ignored) {
                    // fail-open
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
                if (moduleMs > 0) {
                    Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                host.maybeEnqueuePrune(cache);
            }
            if (result.success() && preGraph != null && preFps != null) {
                PreflightMemo.storeDirty(entryDir, preGraph, skipTests, Set.of(), preFps);
                PreflightMemo.storeGraph(entryDir, preGraph);
                ModuleInputProvenance.record(entryDir, preGraph, preFps);
            }
            return outcome;
        } catch (Exception e) {
            // The build threw before it could rule. Declining here would hand the journal a run
            // with no failure rows, which derives green — a build that never finished, recorded
            // as a success.
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
