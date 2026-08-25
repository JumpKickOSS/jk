// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.TestSupport;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Set;

/** Single-project {@code test-request}. */
public final class TestVerb implements HostedVerb {

    private final VerbHost host;

    public TestVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.TEST_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("test");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-test-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, ProtoJobs.JDKS_DIR);
            int workers = Jsonl.intValue(requestLine, "workers", 0);
            String profile = Jsonl.str(requestLine, "profile");
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            // Default parallel, same as every other surface (JK-2213): a serial default here
            // put concurrent single-module test jobs behind the process-wide TEST_GATE.
            boolean parallelTests = Jsonl.bool(requestLine, "parallelTests", true);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve(ManifestPaths.MANIFEST);
            Path lockFile = LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            boolean compactTests = ModuleLayout.isCompact(entryDir);
            int estimatedTestCount = TestSupport.estimateSelectedSuiteTestCount(
                    entryDir, compactTests, ProtoJobs.testSelectionOf(requestLine));

            JkConfig config = JkConfig.empty()
                    .withOffline(offline)
                    .withRebuild(Jsonl.bool(requestLine, "rebuild", false))
                    .withVerbose(verbose)
                    .withForce(force);
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withJvm(ProtoSession.jvmTuning(requestLine))
                    .withParallelTests(parallelTests)
                    .withTestSelection(ProtoJobs.testSelectionOf(requestLine));

            BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                            entryDir,
                            cache,
                            buildFile,
                            lockFile,
                            lockFile.getParent(),
                            workerCount,
                            estimatedTestCount,
                            profile,
                            jdksDir,
                            /* skipTests */ false,
                            verbose,
                            /* testOnly */ true,
                            /* compileOnly */ false,
                            Set.of(),
                            session)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine));
            BuildPlan plan = BuildPlanner.coreBuilder(inputs).build();

            PlanBurst.announce(host, plan, writer);
            BuildPlanResult result = SessionContext.where(session, plan::run);
            host.releaseExclusiveSlot();
            host.accTests(
                    host.eventRequestId(), plan.get(BuildPlanner.TEST_RESULT).orElse(null));
            return result.success() ? JobOutcome.ok() : JobOutcome.failed(Exit.FAILURE);
        } catch (Exception e) {
            // The run threw before it could rule. Declining here would hand the journal a run
            // with no failure rows, which derives green — a test run that never finished,
            // recorded as a success.
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
