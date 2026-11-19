// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.runtime.base.RungBias;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a finished run teaches the host lands in the host store of the builds root the run is
 * journaled under, whatever state directory the process resolves when the record is written.
 */
class BuildJournalHostStoreTest {

    private static final String STATE_DIR = "jk.env.JK_STATE_DIR";

    /** An absolute project directory in this host's spelling, as a run records it. */
    private static final String PROJ = Path.of("/proj").toAbsolutePath().toString();

    /**
     * A journal finishing a run while the process's state directory points elsewhere (another test's,
     * in one test JVM) taught that directory's host its rung bias, and an estimate priced there
     * between two reads moved by the suite's share: 3600 ms read as 3150.
     */
    @Test
    void a_finished_runs_rung_bias_goes_to_its_own_builds_roots_host_store(@TempDir Path tmp) {
        Path own = tmp.resolve("own/builds");
        Path elsewhere = tmp.resolve("elsewhere");
        String prev = System.getProperty(STATE_DIR);
        System.setProperty(STATE_DIR, elsewhere.toString());
        try {
            BuildJournal journal = new BuildJournal(own);
            long requestId = 5151L;
            BuildRecord run = BuildRecord.running(
                    0, "build", PROJ, "g:a", null, 1_700_000_000_000L, "9.9", "cli", null, requestId);
            String locator = requireNonNull(journal.begin(run));
            // Priced at 3 s from the host's calibration; the suite took 1.5 s.
            EtaModels.put(
                    requestId,
                    new WorkModel(
                            3_000,
                            1,
                            false,
                            true,
                            List.of(),
                            Map.of(
                                    Path.of(PROJ),
                                    List.of(new StepCost(
                                            "run-tests",
                                            20,
                                            StepCost.Kind.TEST,
                                            0.45,
                                            0,
                                            StepCost.Rung.CALIBRATION)))));

            assertThat(journal.complete(locator, finished(requestId), BuildJournal.Snapshot.NONE))
                    .isTrue();

            assertThat(RungBias.load(own.resolve(HostStore.FILE)).factor(StepCost.Rung.CALIBRATION, "run-tests"))
                    .as("the run's own host learned that its suites run faster than priced")
                    .isLessThan(1.0);
            assertThat(RungBias.load(elsewhere.resolve("builds").resolve(HostStore.FILE))
                            .factor(StepCost.Rung.CALIBRATION, "run-tests"))
                    .as("the state directory the process resolves now learned nothing of it")
                    .isEqualTo(1.0);
        } finally {
            if (prev == null) System.clearProperty(STATE_DIR);
            else System.setProperty(STATE_DIR, prev);
        }
    }

    private static BuildRecord finished(long requestId) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                PROJ,
                "g:a",
                null,
                1_700_000_000_000L,
                1_700_000_001_500L,
                1_500,
                true,
                false,
                0,
                "9.9",
                null,
                List.of(),
                List.of(new BuildRecord.Task("run-tests", "test", "SUCCESS", 1_500, 0)),
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                requestId,
                null,
                List.of());
    }
}
