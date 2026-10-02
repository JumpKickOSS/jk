// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The refusal that names the path under its free-space floor. */
class DiskFloorTest {

    @Test
    void a_short_volume_is_refused_by_name_after_the_wait(@TempDir Path dir) {
        Probe fixed = new Probe(100L << 20, 100L << 30);
        DiskFloor.Outcome outcome = DiskFloor.await(List.of(dir), fixed, () -> false, 0);
        assertThat(outcome.halted()).isFalse();
        DiskFloor.Shortage shortage = outcome.shortage();
        assertThat(shortage).isNotNull();
        assertThat(Objects.requireNonNull(shortage).message())
                .contains(dir.toString())
                .contains("free")
                .contains("need");
    }

    @Test
    void enough_space_admits_at_once(@TempDir Path dir) {
        Probe fixed = new Probe(50L << 30, 100L << 30);
        DiskFloor.Outcome outcome = DiskFloor.await(List.of(dir), fixed, () -> false, 5_000);
        assertThat(outcome.shortage()).isNull();
        assertThat(outcome.halted()).isFalse();
    }

    @Test
    void a_stop_during_the_wait_does_not_report_a_shortage(@TempDir Path dir) {
        Probe fixed = new Probe(1, 100L << 30);
        DiskFloor.Outcome outcome = DiskFloor.await(List.of(dir), fixed, () -> true, 5_000);
        assertThat(outcome.halted()).isTrue();
        assertThat(outcome.shortage()).isNull();
    }

    private record Probe(long usable, long total) implements DiskFloor.Probe {
        @Override
        public long usable(Path path) {
            return usable;
        }

        @Override
        public long total(Path path) {
            return total;
        }

        @Override
        public String volume(Path path) {
            return "vol";
        }
    }
}
