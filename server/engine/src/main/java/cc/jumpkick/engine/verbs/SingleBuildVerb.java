// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.PreflightMemo;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            int workers = Jsonl.intValue(requestLine, "workers", 0);
            String profile = Jsonl.str(requestLine, "profile");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            int estimatedTestCount = skipTests
                    ? 0
                    : cc.jumpkick.runtime.TestSupport.estimateSelectedSuiteTestCount(
                            entryDir,
                            cc.jumpkick.layout.ModuleLayout.isCompact(entryDir),
                            ProtoJobs.testSelectionOf(requestLine));

            Session session =
                    host.resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);

            cc.jumpkick.runtime.BuildPlanner.Inputs inputs = new cc.jumpkick.runtime.BuildPlanner.Inputs(
                            entryDir,
                            cache,
                            buildFile,
                            lockFile,
                            lockFile.getParent(),
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
            cc.jumpkick.run.BuildPlan plan = SessionContext.where(session, () -> {
                cc.jumpkick.run.BuildPlan.Builder builder = cc.jumpkick.runtime.BuildPlanner.coreBuilder(inputs, false);
                cc.jumpkick.runtime.BuildPlanner.appendDeclaredTails(builder, inputs);
                return builder.build();
            });
            long barWeight = plan.estimatedTotalWeight();

            PlanBurst.announce(host, plan, writer);

            BuildGraph.Result preGraph = null;
            Map<Path, String> preFps = null;
            if (!session.config().rebuildOr(false) && !session.config().forceOr(false)) {
                try {
                    cc.jumpkick.model.JkBuild entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
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
            cc.jumpkick.run.BuildPlanResult result = SessionContext.where(session, plan::run);
            host.releaseExclusiveSlot();
            host.accTests(
                    host.eventRequestId(),
                    plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
            cc.jumpkick.engine.jobs.JobOutcome outcome =
                    cc.jumpkick.engine.jobs.JobOutcome.of(result.success(), result.success() ? 0 : 1);
            if (result.success() && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    cc.jumpkick.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                host.maybeEnqueuePrune(cache);
            }
            if (result.success() && preGraph != null && preFps != null) {
                PreflightMemo.storeDirty(entryDir, preGraph, skipTests, Set.of(), preFps);
                PreflightMemo.storeGraph(entryDir, preGraph);
            }
            return outcome;
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return null;
        }
    }
}
