// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Request;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite's settled reads replace its module's reads part only where the preflight saw what it
 * started from, and only for the build whose suite settled them.
 */
class SettledReadsTest {

    @Test
    void a_suite_that_started_from_the_preflights_reads_amends_them(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("app");

        Map<Path, String> out = inBuild(() -> {
            SettledReads.record(app, "aaa", "bbb");
            return SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "aaa"));
        });

        assertThat(out).containsEntry(app, "f00" + SettledReads.READS + "bbb");
    }

    @Test
    void reads_that_moved_after_the_preflight_are_kept_so_the_next_build_schedules_the_module(@TempDir Path tmp)
            throws Exception {
        Path app = tmp.resolve("app");

        Map<Path, String> out = inBuild(() -> {
            SettledReads.record(app, "aaa", "bbb");
            return SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "zzz"));
        });

        assertThat(out).containsEntry(app, "f00" + SettledReads.READS + "zzz");
    }

    @Test
    void a_settled_entry_is_spent_by_the_build_that_certifies(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("app");

        Map<Path, String> later = inBuild(() -> {
            SettledReads.record(app, "aaa", "bbb");
            SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "zzz"));
            return SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "aaa"));
        });

        assertThat(later).containsEntry(app, "f00" + SettledReads.READS + "aaa");
    }

    @Test
    void what_one_build_settled_never_amends_another_builds_memo(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("app");
        inBuild(() -> {
            SettledReads.record(app, "aaa", "bbb");
            return null;
        });

        Map<Path, String> other = inBuild(() -> SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "aaa")));
        SettledReads.record(app, "aaa", "ccc");
        Map<Path, String> offRequest = SettledReads.amend(Map.of(app, "f00" + SettledReads.READS + "aaa"));

        assertThat(other).containsEntry(app, "f00" + SettledReads.READS + "aaa");
        assertThat(offRequest)
                .as("off a request nothing is settled")
                .containsEntry(app, "f00" + SettledReads.READS + "aaa");
    }

    private static <T> T inBuild(Callable<T> body) throws Exception {
        return Request.detached().with(SettledReads.SLOT, new SettledReads()).call(body::call);
    }
}
