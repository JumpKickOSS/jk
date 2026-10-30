// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdateStateTest {

    private static final Instant T = Instant.parse("2026-10-30T09:00:00Z");

    @Test
    void every_field_round_trips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("self-update.toml");
        SelfUpdateState written = SelfUpdateState.update(file, s -> s.withCheck(T, "0.17.0")
                .withFirstSeen(T.minusSeconds(60))
                .withHold("0.16.2", "0.17.0")
                .withSnoozeUntil(T.plusSeconds(3600))
                .withInteractiveSeen(T)
                .withNotice("0.16.2"));

        assertThat(SelfUpdateState.read(file)).isEqualTo(written);
        assertThat(written.isHeld()).isTrue();
    }

    @Test
    void a_failed_check_moves_only_the_attempt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("self-update.toml");
        SelfUpdateState.update(file, s -> s.withCheck(T, "0.17.0"));
        SelfUpdateState after = SelfUpdateState.update(file, s -> s.withAttempt(T.plusSeconds(7200)));

        assertThat(after.lastCheck()).isEqualTo(T);
        assertThat(after.lastAttempt()).isEqualTo(T.plusSeconds(7200));
        assertThat(after.latest()).isEqualTo("0.17.0");
    }

    @Test
    void clearing_the_hold_keeps_everything_else(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("self-update.toml");
        SelfUpdateState.update(file, s -> s.withCheck(T, "0.17.0").withHold("0.16.2", "0.17.0"));
        SelfUpdateState after = SelfUpdateState.update(file, SelfUpdateState::withoutHold);

        assertThat(after.isHeld()).isFalse();
        assertThat(after.heldFrom()).isNull();
        assertThat(after.latest()).isEqualTo("0.17.0");
    }

    @Test
    void an_absent_or_garbled_file_reads_as_empty(@TempDir Path dir) throws Exception {
        assertThat(SelfUpdateState.read(dir.resolve("none.toml"))).isEqualTo(SelfUpdateState.EMPTY);
        Path garbled = Files.writeString(dir.resolve("g.toml"), "last-check = \"yesterday\"\n");
        assertThat(SelfUpdateState.read(garbled).lastCheck()).isNull();
    }
}
