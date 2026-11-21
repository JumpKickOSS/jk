// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FailureSnapshotsTest {

    private static final Map<String, String> PRINTED = new HashMap<>();

    /** Runs {@link FailureSnapshotFixture} once, under the agent and flags the engine starts a test JVM with. */
    @BeforeAll
    static void runFixture() throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                List.of(
                        java.toString(),
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0,quiet=y",
                        "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
                        "-D" + FailureSnapshots.PROPERTY + "=true",
                        "-D" + SnapshotFailingFixture.FAIL + "=true",
                        "-cp",
                        System.getProperty("java.class.path"),
                        FailureSnapshotFixture.class.getName()),
                Duration.ofSeconds(60));
        String out = run.output();
        assertThat(run.ok()).as(out).isTrue();
        for (String line : out.split("\\R")) {
            int space = line.indexOf(' ');
            if (space > 0) PRINTED.put(line.substring(0, space), line.substring(space + 1));
        }
    }

    @Test
    void a_project_throw_carries_the_arguments_and_locals_of_each_project_frame() {
        assertThat(PRINTED.get("thrownHere"))
                .contains("\"at\":\"FailureSnapshotFixture.ship(FailureSnapshotFixture.java:")
                .contains("order=FailureSnapshotFixture$Order{id=7, customer=\\\"ada\\\", lines=ArrayList[2]}")
                .contains("\"qty=3\"")
                .contains("\"speed=FailureSnapshotFixture$Speed.OVERNIGHT\"")
                .contains("\"available=2\"")
                .contains("\"at\":\"FailureSnapshotFixture.thrownHere(FailureSnapshotFixture.java:");
    }

    @Test
    void a_throw_inside_the_jdk_starts_at_the_jdk_frame_and_keeps_the_project_frame_that_called_it() {
        String printed = PRINTED.get("thrownInTheJdk");
        assertThat(printed).startsWith("[{\"at\":\"Objects.requireNonNull(");
        assertThat(printed)
                .contains("\"at\":\"FailureSnapshotFixture.thrownInTheJdk(FailureSnapshotFixture.java:")
                .contains("\"customer=null\"");
    }

    @Test
    void an_exception_the_project_catches_itself_is_not_the_failure() {
        assertThat(PRINTED.get("handledByTheProject"))
                .contains("\"at\":\"FailureSnapshotFixture.handledByTheProject(")
                .contains("\"parsed=-1\"")
                .doesNotContain("FailureSnapshotFixture.parse(");
    }

    @Test
    void a_long_value_is_cut_at_the_budget_and_says_how_much_is_left() {
        String printed = PRINTED.get("longValue");
        String token = "s3cr3t-".repeat(40);
        assertThat(printed).doesNotContain(token).contains(FailureSnapshots.VALUE_TRUNCATION_MARKER);
        assertThat(printed).contains("chars)");
    }

    @Test
    void a_failed_test_s_throwable_carries_its_snapshot_through_the_launcher() {
        assertThat(PRINTED.get("launched"))
                .contains("\"snapshot\":[")
                .contains("\"at\":\"SnapshotFailingFixture.counts_the_lines(SnapshotFailingFixture.java:")
                .contains("\"expected=3\"")
                .contains("lines=List[2] {\\\"A1\\\", \\\"B2\\\"}")
                .doesNotContain("this=SnapshotFailingFixture");
    }

    @Test
    void payload_prefers_the_innermost_cause_that_was_recorded() {
        FailureSnapshots store = new FailureSnapshots();
        long mark = store.mark();
        store.record(
                "main",
                "java.lang.IllegalStateException",
                "inner",
                List.of(new FailureSnapshots.Frame("A.a(A.java:1)", List.of("x=1"))));
        store.record(
                "main",
                "java.lang.RuntimeException",
                "outer",
                List.of(new FailureSnapshots.Frame("B.b(B.java:2)", List.of("y=2"))));
        Throwable failure = new RuntimeException("outer", new IllegalStateException("inner"));
        assertThat(JsonOut.string(store.payload(failure, mark, "main")))
                .contains("A.a(A.java:1)")
                .doesNotContain("B.b");
        assertThat(store.payload(failure, store.mark(), "main")).isNull();
    }
}
