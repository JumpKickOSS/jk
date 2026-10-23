// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A suite's settled reads replace its module's reads part only where the preflight saw what it started from. */
class SettledReadsTest {

    @Test
    void a_suite_that_started_from_the_preflights_reads_amends_them(@TempDir Path tmp) {
        Path app = tmp.resolve("app");
        SettledReads.record(app, "aaa", "bbb");

        Map<Path, String> out = SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "aaa"));

        assertThat(out).containsEntry(app, "f00" + SettledReads.READS + "bbb");
    }

    @Test
    void reads_that_moved_after_the_preflight_are_kept_so_the_next_build_schedules_the_module(@TempDir Path tmp) {
        Path app = tmp.resolve("app");
        SettledReads.record(app, "aaa", "bbb");

        Map<Path, String> out = SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "zzz"));

        assertThat(out).containsEntry(app, "f00" + SettledReads.READS + "zzz");
    }

    @Test
    void a_settled_entry_is_spent_by_the_build_that_certifies(@TempDir Path tmp) {
        Path app = tmp.resolve("app");
        SettledReads.record(app, "aaa", "bbb");
        SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "zzz"));

        Map<Path, String> later = SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "aaa"));

        assertThat(later).containsEntry(app, "f00" + SettledReads.READS + "aaa");
    }
}
