// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a verb's verdict becomes once it reaches the journal. The process exit code was never the
 * problem here — a verb that threw already made the CLI exit non-zero — so every assertion below
 * reads the persisted {@link BuildRecord}, not the wire and not a return code.
 *
 * <p>Two directions, one cause. A body that threw and declined to rule left the journal a run with
 * no failure rows, which derives green. A body that succeeded and declined to rule left the journal
 * no verdict at all, and the benign end-of-request EOF then stamped it cancelled.
 */
class VerbVerdictJournalTest {

    /**
     * Exactly what {@code JournalWriter} persists: the accumulator's own record, with the cancel
     * flag the envelope resolved OR-ed into it.
     *
     * @param cancelToken whether the request's cancel token had tripped by request-finish — true
     *     for every socket job, because the engine half-closes the client's read to wake the
     *     connection thread once the runner is done
     */
    private static BuildRecord journal(BuildAccumulator acc, boolean cancelToken) {
        boolean envelopeCancelled = acc.wasCancelled() || (cancelToken && !acc.hasOutcome());
        return acc.toRecord(1_000L, envelopeCancelled || acc.wasCancelled(), 10L, "test", null);
    }

    private static BuildAccumulator accumulator(String kind) {
        return new BuildAccumulator(kind, "/w", "g:w", "cli");
    }

    // --- a verb that threw -------------------------------------------------------------------

    @Test
    void a_single_build_whose_verb_throws_journals_a_failure() {
        RecordingHost host = new RecordingHost();
        // No "dir" in the request: the verb throws on its very first line, before any plan runs.
        JobOutcome outcome = new SingleBuildVerb(host).run("{\"type\":\"single-build-request\"}", token(), null);

        assertThat(host.quietSends)
                .as("guard: the verb really did fail and say so on the wire")
                .anyMatch(line -> EngineProtocol.ERROR.equals(EngineProtocol.typeOf(line))
                        || line.contains(EngineProtocol.ERR_REQUEST_FAILED));

        BuildAccumulator acc = accumulator("build");
        acc.stamp(outcome);
        BuildRecord record = journal(acc, true);
        assertThat(record.success()).isFalse();
        assertThat(record.exitCode()).isNotZero();
        assertThat(record.cancelled()).isFalse();
    }

    @Test
    void a_test_run_whose_verb_throws_journals_a_failure() {
        RecordingHost host = new RecordingHost();
        JobOutcome outcome = new TestVerb(host).run("{\"type\":\"test-request\"}", token(), null);

        BuildAccumulator acc = accumulator("test");
        acc.stamp(outcome);
        BuildRecord record = journal(acc, true);
        assertThat(record.success()).isFalse();
        assertThat(record.exitCode()).isNotZero();
        assertThat(record.cancelled()).isFalse();
    }

    /**
     * The durable half: even if an eighth verb declines after producing nothing — or the runner is
     * abandoned before its first row — the journal refuses to read silence as success.
     */
    @Test
    void a_declined_verdict_with_no_rows_at_all_journals_a_failure_and_says_why() {
        BuildAccumulator acc = accumulator("build");
        acc.stamp(JobOutcome.declined());

        BuildRecord record = journal(acc, false);
        assertThat(record.success()).isFalse();
        assertThat(record.exitCode()).isNotZero();
        assertThat(record.diagnostics()).extracting(BuildRecord.Diag::code).contains("no-verdict");
    }

    /** Guards the test above: declining is still honest when the run left rows behind. */
    @Test
    void a_declined_verdict_with_a_green_plan_behind_it_still_journals_success() {
        BuildAccumulator acc = accumulator("build");
        acc.addBuildPlan("", new BuildPlanResult("prune", true, Duration.ZERO, List.of(), List.of(), List.of(), false));
        acc.stamp(JobOutcome.declined());

        BuildRecord record = journal(acc, false);
        assertThat(record.success()).isTrue();
        assertThat(record.diagnostics()).extracting(BuildRecord.Diag::code).doesNotContain("no-verdict");
    }

    // --- a verb that succeeded ----------------------------------------------------------------

    @Test
    void a_successful_cache_prune_journals_success_not_cancelled(@TempDir Path cache) {
        RecordingHost host = new RecordingHost();
        String request = "{\"type\":\"" + EngineProtocol.CACHE_PRUNE_REQUEST + "\",\"op\":\"prune\",\"cache\":"
                + Jsonl.quote(cache.toString()) + ",\"dryRun\":true}";

        JobOutcome outcome = new CacheMaintenanceVerb(host).run(request, token(), null);

        assertThat(host.quietSends)
                .as("guard: the prune plan really ran and finished")
                .anyMatch(line -> EngineProtocol.BUILDPLAN_FINISH.equals(EngineProtocol.typeOf(line)));

        BuildAccumulator acc = accumulator("cache");
        acc.stamp(outcome);
        // The engine wakes its own connection thread by half-closing the client's read once the
        // runner is done; that EOF arrives as a non-explicit cancel signal on every socket job.
        acc.markUserCancelled(false);

        BuildRecord record = journal(acc, true);
        assertThat(record.cancelled()).isFalse();
        assertThat(record.success()).isTrue();
        assertThat(record.exitCode()).isZero();
    }

    private static Session.CancelToken token() {
        return Session.CancelToken.live();
    }

    /** Enough host for a verb to run a plan and write its wire lines somewhere countable. */
    private static final class RecordingHost implements VerbHost {
        private final List<String> quietSends = new ArrayList<>();
        private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

        @Override
        public void send(@Nullable BufferedWriter writer, String line) {
            quietSends.add(line);
        }

        @Override
        public void sendQuiet(@Nullable BufferedWriter writer, String line) {
            quietSends.add(line);
        }

        @Override
        public ReentrantReadWriteLock cacheGate() {
            return gate;
        }

        @Override
        public void publishRequestError(long rid, @Nullable String dir, String message) {}

        @Override
        public long eventRequestId() {
            return 0L;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter writer, String dir) {
            return new WorkspaceBuildListener() {};
        }

        @Override
        public BuildPlanListener planListener(String dir, @Nullable BufferedWriter writer, BuildPlan plan) {
            return new BuildPlanListener() {};
        }

        @Override
        public BuildPlanListener planListener(
                String dir,
                @Nullable BufferedWriter writer,
                @Nullable Function<BuildPlanResult, String> finishEncoder) {
            return new BuildPlanListener() {
                @Override
                public void planFinish(BuildPlanResult result) {
                    if (finishEncoder != null) quietSends.add(finishEncoder.apply(result));
                }
            };
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return tokenCancelled;
        }

        @Override
        public void accTests(long rid, @Nullable TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter writer, boolean force) {}

        @Override
        public void flushTimeline(long rid, @Nullable BufferedWriter writer) {}

        @Override
        public @Nullable String redactEnv(@Nullable String dir, @Nullable String text) {
            return text;
        }

        @Override
        public String requestFailedLine(@Nullable String dir, Throwable e) {
            return "{\"type\":\"" + EngineProtocol.ERROR + "\",\"code\":\"" + EngineProtocol.ERR_REQUEST_FAILED + "\"}";
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {}
    }
}
