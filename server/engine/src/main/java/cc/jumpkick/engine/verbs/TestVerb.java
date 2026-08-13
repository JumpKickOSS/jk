// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Optional;
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
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            int workers = Jsonl.intValue(requestLine, "workers", 0);
            String profile = Jsonl.str(requestLine, "profile");
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean parallelTests = Jsonl.bool(requestLine, "parallelTests", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            boolean compactTests = cc.jumpkick.layout.ModuleLayout.isCompact(entryDir);
            int estimatedTestCount = cc.jumpkick.runtime.TestSupport.estimateSelectedSuiteTestCount(
                    entryDir, compactTests, EngineProtocol.testSelectionOf(requestLine));

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine))
                    .withParallelTests(parallelTests)
                    .withTestSelection(EngineProtocol.testSelectionOf(requestLine));

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
                            /* skipTests */ false,
                            verbose,
                            /* testOnly */ true,
                            /* compileOnly */ false,
                            Set.of(),
                            session)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            cc.jumpkick.run.BuildPlan plan =
                    cc.jumpkick.runtime.BuildPlanner.coreBuilder(inputs).build();

            PlanBurst.announce(host, plan, writer);
            cc.jumpkick.run.BuildPlanResult result = SessionContext.where(session, plan::run);
            host.releaseExclusiveSlot();
            host.accTests(
                    host.eventRequestId(),
                    plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
            host.accOutcome(host.eventRequestId(), result.success(), result.success() ? 0 : 1);
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
