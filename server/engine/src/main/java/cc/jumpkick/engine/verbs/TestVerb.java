// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.TestSupport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.TestRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            TestRequest body = TestRequest.decode(requestLine);
            String entryDirStr = body.dir();
            String cacheStr = body.cache();
            String jdksDirStr = body.jdksDir();
            int workers = body.workers();
            String profile = body.profile();
            boolean verbose = body.verbose();
            // Default parallel, same as every other surface: a serial default here
            // put concurrent single-module test jobs behind the process-wide TEST_GATE.
            boolean parallelTests = body.parallelTests();

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve(ManifestPaths.MANIFEST);
            Path lockFile = LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            boolean compactTests = ModuleLayout.isCompact(entryDir);
            int estimatedTestCount =
                    TestSupport.estimateSelectedSuiteTestCount(entryDir, compactTests, body.selection());

            JkConfig config = JkConfig.empty()
                    .withOffline(body.offline())
                    .withRebuild(Jsonl.bool(requestLine, "rebuild", false))
                    .withVerbose(verbose)
                    .withForce(body.force());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withJvm(ProtoSession.jvmTuning(requestLine))
                    .withParallelTests(parallelTests)
                    .withTestSelection(body.selection())
                    .withAffected(Jsonl.bool(requestLine, "affected", false))
                    // The request's env belongs on the session too, not only on the request: it is
                    // what BuildEnv hands every build-path caller, and without it `FOO=x jk build`
                    // reached variant `env:` indirection (which is passed the request's map
                    // directly) but nothing that asked BuildEnv — so `[test] env` resolved against
                    // the daemon's own environment instead of the caller's.
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    // The request's toolchain selection belongs on it too: without this the SWITCH tier is
                    // empty and a resident engine ignores both --jdk and JK_JDK.
                    .withToolchainSpecs(
                            ProtoSession.jdkSpecOf(requestLine),
                            ProtoSession.graalSpecOf(requestLine),
                            ProtoSession.graalHomeOf(requestLine));

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
            host.accAffected(
                    host.eventRequestId(), plan.get(BuildPlanner.AFFECTED_TESTS).orElse(null));
            if (result.success()) return JobOutcome.ok();
            for (var d : result.errors()) {
                if ("affected-refuse".equals(d.code())) return JobOutcome.failed(Exit.CONFIG);
            }
            return JobOutcome.failed(Exit.FAILURE);
        } catch (Exception e) {
            // The run threw before it could rule. Declining here would hand the journal a run
            // with no failure rows, which derives green — a test run that never finished,
            // recorded as a success.
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
