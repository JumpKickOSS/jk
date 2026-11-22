// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.updates.UpdateState;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.HostRow.Status;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code updates} row: off, not checked yet, or the last check and what waits to be applied. */
class DoctorUpdatesRowTest {

    @TempDir
    Path dir;

    @Test
    void says_when_the_check_is_off_or_has_not_run() {
        assertThat(DoctorChecks.updates(dir, false).detail()).isEqualTo("off ([updates] check = false)");
        HostRow none = DoctorChecks.updates(dir, true);
        assertThat(none.status()).isEqualTo(Status.OK);
        assertThat(none.detail()).startsWith("not checked yet");
    }

    @Test
    void shows_the_last_check_and_what_waits() throws IOException {
        UpdateState.NONE
                .checked(Instant.parse("2026-11-22T05:06:07Z"), "manifest published 2026-11-22T04:00:00Z")
                .withPending(Map.of("prices", "v2"))
                .write(dir);

        HostRow row = DoctorChecks.updates(dir, true);

        assertThat(row.status()).isEqualTo(Status.OK);
        assertThat(row.detail())
                .isEqualTo("last check 2026-11-22T05:06:00Z: manifest published 2026-11-22T04:00:00Z; waiting: prices");
    }

    @Test
    void a_failed_check_is_a_warning() throws IOException {
        UpdateState.NONE
                .checked(Instant.parse("2026-11-22T05:06:07Z"), "failed: no source answered")
                .write(dir);

        HostRow row = DoctorChecks.updates(dir, true);

        assertThat(row.status()).isEqualTo(Status.WARN);
        assertThat(row.detail()).isEqualTo("last check 2026-11-22T05:06:00Z: failed: no source answered");
    }
}
