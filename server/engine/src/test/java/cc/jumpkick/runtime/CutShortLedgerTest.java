// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.orchestrator.TestProgressListener;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.InputGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite cut short (cancelled part way) keeps the classes it got through only when nothing it
 * read moved while it ran: the same check a finished run's marker is held to.
 */
class CutShortLedgerTest {

    private static final String STAMP = "c".repeat(64);
    private static final String TASK = "run-tests@" + "c".repeat(12); // the shape ActionKey.qualifiedTaskId gives

    @TempDir
    Path tmp;

    private ActionCache cache;
    private Path input;

    @BeforeEach
    void setUp() throws Exception {
        cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        input = Files.writeString(
                Files.createDirectories(tmp.resolve("classes")).resolve("A.class"), "one");
    }

    /** A ledger under {@link #STAMP} that recorded {@code demo.OkTest} green. */
    private TestClassLedger oneGreenClass() {
        TestClassLedger ledger = TestClassLedger.load(cache, STAMP, "g:app", "test");
        TestProgressListener l = ledger.recording(TestProgressListener.noop());
        String cls = "[engine:junit-jupiter]/[class:demo.OkTest]";
        l.onTestFinished(cls + "/[method:t()]", "t", "SUCCESSFUL", true, true, 5, 0);
        l.onTestFinished(cls, "OkTest", "SUCCESSFUL", false, true, 10, 0);
        return ledger;
    }

    private List<String> settled() {
        return TestClassLedger.load(cache, STAMP, "g:app", "test").settledClasses(SuiteResources.ONE_LAUNCH);
    }

    @Test
    void a_cut_short_run_whose_inputs_held_keeps_the_classes_it_got_through() throws Exception {
        InputGuard guard = InputGuard.of(List.of(input));
        TestSteps.storeCutShort(oneGreenClass(), false, cache, guard, () -> STAMP, TASK, STAMP);
        assertThat(settled()).containsExactly("demo.OkTest");
    }

    @Test
    void a_cut_short_run_whose_inputs_moved_while_it_ran_keeps_nothing() throws Exception {
        InputGuard guard = InputGuard.of(List.of(input));
        Files.writeString(input, "two");
        TestSteps.storeCutShort(oneGreenClass(), false, cache, guard, () -> STAMP, TASK, STAMP);
        assertThat(settled())
                .as("the class passed against bytes the key does not name")
                .isEmpty();
    }

    @Test
    void a_launcher_that_failed_leaves_no_class_settled() throws Exception {
        TestSteps.storeCutShort(oneGreenClass(), false, cache, InputGuard.of(List.of(input)), () -> STAMP, TASK, STAMP);
        TestSteps.storeCutShort(oneGreenClass(), true, cache, InputGuard.of(List.of(input)), () -> STAMP, TASK, STAMP);
        assertThat(settled())
                .as("the class a dead JVM never reported may be the one an earlier run settled")
                .isEmpty();
    }

    @Test
    void a_cut_short_run_whose_key_moved_while_it_ran_keeps_nothing() throws Exception {
        InputGuard guard = InputGuard.of(List.of(input));
        TestSteps.storeCutShort(oneGreenClass(), false, cache, guard, () -> "d".repeat(64), TASK, STAMP);
        assertThat(settled()).isEmpty();
    }
}
