// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Redacted;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The nuke client deletes the cache root — {@code .prune.lock} included — the instant it reads
 * {@code BUILDPLAN_FINISH}, so the engine must have released the file lock by then. The probe
 * re-locks from the same JVM at the moment the terminal is sent: an overlap
 * ({@link OverlappingFileLockException}) means the terminal went out while the lock was held.
 */
class CacheMaintenanceVerbFinishOrderTest {

    @Test
    void the_terminal_is_sent_only_after_the_prune_lock_is_released(@TempDir Path cache) throws Exception {
        ProbeHost host = new ProbeHost(cache);
        CacheMaintenanceVerb verb = new CacheMaintenanceVerb(host);
        String line = "{\"type\":\"cache-prune-request\",\"op\":\"prune\",\"cache\":" + Jsonl.quote(cache.toString())
                + ",\"dryRun\":true}";

        JobOutcome outcome = verb.run(line, Session.CancelToken.live(), new BufferedWriter(new StringWriter()));

        assertThat(outcome).isInstanceOf(JobOutcome.Succeeded.class);
        assertThat(host.sawFinish).isTrue();
        assertThat(host.overlapAtFinish)
                .as("the engine still held .prune.lock when the terminal was sent")
                .isFalse();
        assertThat(host.lockFreeAtFinish).isTrue();
    }

    /** Only the seams this flow touches are real; the finish probe lives in {@link #sendQuiet}. */
    private static final class ProbeHost implements VerbHost {

        private final Path cache;
        private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
        volatile boolean sawFinish;
        volatile boolean overlapAtFinish;
        volatile boolean lockFreeAtFinish;

        private ProbeHost(Path cache) {
            this.cache = cache;
        }

        @Override
        public void sendQuiet(BufferedWriter writer, String wireLine) {
            if (!EngineProtocol.BUILDPLAN_FINISH.equals(EngineProtocol.typeOf(wireLine))) return;
            sawFinish = true;
            try (FileChannel ch = FileChannel.open(
                    CacheTree.PRUNE_LOCK.under(cache), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = ch.tryLock();
                if (lock != null) {
                    lockFreeAtFinish = true;
                    lock.release();
                }
            } catch (OverlappingFileLockException e) {
                overlapAtFinish = true;
            } catch (IOException ignored) {
                // the probe's own channel failed — neither flag set, the test fails visibly
            }
        }

        @Override
        public ReentrantReadWriteLock cacheGate() {
            return gate;
        }

        @Override
        public long eventRequestId() {
            return 1;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(BufferedWriter writer, String dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(String dir, BufferedWriter writer, BuildPlan plan) {
            return new BuildPlanListener() {};
        }

        @Override
        public BuildPlanListener planListener(
                String dir, BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
            return new BuildPlanListener() {};
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return tokenCancelled;
        }

        @Override
        public void accTests(long rid, TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force) {}

        @Override
        public void flushTimeline(long rid, BufferedWriter writer) {}

        @Override
        public void send(BufferedWriter writer, String wireLine) {}

        @Override
        public String redactEnv(String dir, String text) {
            return text;
        }

        @Override
        public List<Redacted> redactErrors(String dir, List<String> errors) {
            return List.of();
        }

        @Override
        public String requestFailedLine(String dir, Throwable e) {
            return "request-failed:" + e;
        }

        @Override
        public void publishRequestError(long rid, String dir, String message) {}

        @Override
        public void maybeEnqueuePrune(Path cachePath) {}
    }
}
