// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.MvnResultsRequest;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Maven run on a POM that cannot name its project — no {@code <version>} of its own or its
 * parent's — is refused loudly: the answer names the POM and the element it lacks, and no journal
 * row is written for it, instead of a row under build number 0 and a log line nobody reads.
 */
class MvnResultsRefusalTest {

    @Test
    void a_version_less_pom_is_refused_by_name_and_leaves_no_row(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("pom.xml"), "<project><groupId>demo</groupId><artifactId>nover</artifactId></project>");
        Path events = events(dir, "demo:nover");
        RecordingHost host = new RecordingHost();

        JobOutcome out = new MvnResultsVerb(host)
                .run(
                        new MvnResultsRequest(dir.toString(), events.toString(), 1, 900L, "compile").encode(),
                        Session.CancelToken.live(),
                        null);

        assertThat(out).isInstanceOf(JobOutcome.Failed.class);
        assertThat(host.discarded).isTrue();
        assertThat(host.modules).isEmpty();
        assertThat(host.sent).hasSize(1);
        assertThat(host.sent.getFirst())
                .contains("mvn-results-result")
                .contains(dir.resolve("pom.xml") + " has no <version> and no <parent><version>");
    }

    @Test
    void a_pom_whose_parent_carries_the_version_is_journaled(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("pom.xml"),
                "<project><parent><groupId>demo</groupId><version>2.0</version></parent>"
                        + "<artifactId>app</artifactId></project>");
        Path events = events(dir, "demo:app");
        RecordingHost host = new RecordingHost();

        JobOutcome out = new MvnResultsVerb(host)
                .run(
                        new MvnResultsRequest(dir.toString(), events.toString(), 0, 900L, "compile").encode(),
                        Session.CancelToken.live(),
                        null);

        assertThat(out).isInstanceOf(JobOutcome.Succeeded.class);
        assertThat(host.discarded).isFalse();
        assertThat(host.modules).hasSize(1);
        assertThat(host.toolWall).isEqualTo(900L);
        assertThat(host.sent.getFirst()).contains("mvn-results-result").contains("\"error\":null");
    }

    /** One succeeded module, as the spy records it. */
    private static Path events(Path dir, String coord) throws Exception {
        Path events = dir.resolve("events.tsv");
        String d = dir.toString();
        Files.writeString(
                events,
                line("SessionStarted", "0", "", "", "", "", "", "")
                        + line("ProjectStarted", "0", coord, d, "", "", "", "")
                        + line("ProjectSucceeded", "800", coord, d, "", "", "", ""));
        return events;
    }

    private static String line(String... fields) {
        List<String> enc = new ArrayList<>();
        for (String f : fields) enc.add(URLEncoder.encode(f, StandardCharsets.UTF_8));
        return String.join("\t", enc) + "\n";
    }

    /** A host that keeps what the verb told it. */
    static final class RecordingHost implements VerbHost {
        final List<String> sent = new ArrayList<>();
        final List<ModuleOutcome> modules = new ArrayList<>();
        boolean discarded;
        long toolWall;

        @Override
        public long eventRequestId() {
            return 7;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter w, String dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(String dir, @Nullable BufferedWriter w, BuildPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(
                String dir, @Nullable BufferedWriter w, @Nullable Function<BuildPlanResult, String> enc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return false;
        }

        @Override
        public void accTests(long rid, @Nullable TestSummary tests) {}

        @Override
        public void accModule(long rid, ModuleOutcome outcome) {
            modules.add(outcome);
        }

        @Override
        public void accStepFinish(long rid, String dir, String step, String status, long millis) {}

        @Override
        public void accBuildPlanFinish(long rid, String dir, BuildPlanResult result) {}

        @Override
        public void accToolWall(long rid, long millis) {
            toolWall = millis;
        }

        @Override
        public void discardJournal(long rid) {
            discarded = true;
        }

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter w, boolean force) {}

        @Override
        public void flushTimeline(long rid, @Nullable BufferedWriter w) {}

        @Override
        public void send(@Nullable BufferedWriter w, String line) {
            sent.add(line);
        }

        @Override
        public void sendQuiet(@Nullable BufferedWriter w, String line) {
            sent.add(line);
        }

        @Override
        public @Nullable String redactEnv(@Nullable String dir, @Nullable String text) {
            return text;
        }

        @Override
        public String requestFailedLine(@Nullable String dir, Throwable e) {
            return "request-failed: " + e;
        }

        @Override
        public void publishRequestError(long rid, @Nullable String dir, String message) {}

        @Override
        public void maybeEnqueuePrune(Path cache) {}
    }
}
