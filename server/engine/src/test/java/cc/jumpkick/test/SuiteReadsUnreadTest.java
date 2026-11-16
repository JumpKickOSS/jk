// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.doctor.DoctorChecks;
import cc.jumpkick.winhost.HostRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run whose recordings cannot be read is never silent: the reason comes back with the run, the
 * module keeps a note the forecast reads, the host keeps the last one for {@code jk doctor}, and a
 * later run that is read forgets both.
 */
class SuiteReadsUnreadTest {

    private static Path stateDir() {
        return Objects.requireNonNull(SuiteReads.lastUnreadFile().getParent());
    }

    @Test
    void an_unreadable_recording_says_why(@TempDir Path tmp) throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        Files.writeString(
                recordings.resolve("junk.jfr"), "not a recording, though long enough for a chunk header ......");
        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.complete()).isFalse();
        String why = Objects.requireNonNull(seen.unread());
        assertThat(why).as("no child JVM for a file that is no recording").isEqualTo("junk.jfr: not a recording");
        assertThat(SuiteReads.unreadMessage(why))
                .startsWith("suite recording could not be read: junk.jfr: not a recording")
                .endsWith("; this suite will not replay");
    }

    @Test
    void the_note_is_kept_for_the_forecast_and_doctor_until_a_run_is_read(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        assertThat(SuiteReads.unread(module)).isNull();

        SuiteReads.noteUnread(module, "r.jfr: the JDK's parser ran out of its 1g heap");
        assertThat(SuiteReads.unread(module)).isEqualTo("r.jfr: the JDK's parser ran out of its 1g heap");
        HostRow row = DoctorChecks.suiteReads(stateDir());
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail())
                .contains(module.toAbsolutePath().normalize().toString())
                .contains("suite recording could not be read: r.jfr: the JDK's parser ran out of its 1g heap")
                .contains("this suite will not replay");

        SuiteReads.clearUnread(other);
        assertThat(DoctorChecks.suiteReads(stateDir()).status())
                .as("another module's read run leaves this one's note")
                .isEqualTo(HostRow.Status.WARN);
        SuiteReads.clearUnread(module);
        assertThat(SuiteReads.unread(module)).isNull();
        assertThat(DoctorChecks.suiteReads(stateDir()).status()).isEqualTo(HostRow.Status.OK);
    }
}
