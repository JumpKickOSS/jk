// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkAccessLedgerTest {

    @Test
    void touch_upserts_one_line_per_java_home(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".jk-access.log");
        Path home = tempDir.resolve("temurin-21.0.5").toAbsolutePath().normalize();
        JdkAccessLedger ledger = new JdkAccessLedger(file);

        ledger.touch(home, "21.0.5", "Eclipse");
        ledger.touch(home, "21.0.5", "Eclipse");

        String body = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = body.split("\n");
        assertThat(lines).filteredOn(l -> !l.isEmpty()).hasSize(1);
        JdkAccessLedger.Entry e = JdkAccessLedger.parseLine(lines[0]);
        assertThat(e).isNotNull();
        assertThat(e.accessCount()).isEqualTo(2);
        assertThat(e.version()).isEqualTo("21.0.5");
        assertThat(e.vendor()).isEqualTo("Eclipse");
        assertThat(e.javaHome().toAbsolutePath().normalize()).isEqualTo(home);
    }

    @Test
    void three_jdks_means_three_lines(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".jk-access.log");
        JdkAccessLedger ledger = new JdkAccessLedger(file);
        Path a = tempDir.resolve("a").toAbsolutePath().normalize();
        Path b = tempDir.resolve("b").toAbsolutePath().normalize();
        Path c = tempDir.resolve("c").toAbsolutePath().normalize();

        ledger.touch(a, "21.0.5", "Eclipse");
        ledger.touch(b, "25.0.1", "Amazon");
        ledger.touch(c, "17.0.13", "Azul");
        ledger.touch(a, "21.0.5", "Eclipse"); // bump only a

        var map = ledger.byJavaHome();
        assertThat(map).hasSize(3);
        assertThat(map.get(a.toString()).accessCount()).isEqualTo(2);
        assertThat(map.get(b.toString()).accessCount()).isEqualTo(1);
        assertThat(map.get(c.toString()).accessCount()).isEqualTo(1);

        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(body.lines().filter(l -> !l.isEmpty()).count()).isEqualTo(3);
    }

    @Test
    void most_recent_first_orders_by_timestamp(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".jk-access.log");
        Path old = tempDir.resolve("old").toAbsolutePath().normalize();
        Path mid = tempDir.resolve("mid").toAbsolutePath().normalize();
        Path neu = tempDir.resolve("new").toAbsolutePath().normalize();
        Files.writeString(
                file,
                """
                100|1|17.0.1|Eclipse|%s
                500|2|25.0.1|Amazon|%s
                300|1|21.0.5|Azul|%s
                """
                        .formatted(old, neu, mid),
                StandardCharsets.UTF_8);

        var ordered = new JdkAccessLedger(file).mostRecentFirst();
        assertThat(ordered)
                .extracting(e -> e.javaHome().getFileName().toString())
                .containsExactly("new", "mid", "old");
    }

    @Test
    void parse_line_accepts_psv(@TempDir Path tempDir) {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        String line = "1700000000000|4|21.0.5|Eclipse|" + home;
        JdkAccessLedger.Entry e = JdkAccessLedger.parseLine(line);
        assertThat(e).isNotNull();
        assertThat(e.timestampMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(e.accessCount()).isEqualTo(4);
        assertThat(e.version()).isEqualTo("21.0.5");
        assertThat(e.vendor()).isEqualTo("Eclipse");
        assertThat(e.javaHome().toAbsolutePath().normalize()).isEqualTo(home);
    }

    @Test
    void touch_is_silent_on_io_failure(@TempDir Path tempDir) {
        Path bogus = tempDir.resolve("notADir").resolve("nested").resolve("file");
        try {
            Files.writeString(tempDir.resolve("notADir"), "blocker");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        new JdkAccessLedger(bogus).touch(tempDir.resolve("jdk"), "21", "Eclipse");
        // No assertion — just "didn't throw."
    }

    @Test
    void touch_hit_uses_display_vendor(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".jk-access.log");
        Path home = tempDir.resolve("jdk").toAbsolutePath().normalize();
        JdkHit hit = new JdkHit(home, "25.0.3", JdkVendor.TEMURIN, "jk");
        new JdkAccessLedger(file).touch(hit);

        JdkAccessLedger.Entry e = new JdkAccessLedger(file).byJavaHome().get(home.toString());
        assertThat(e).isNotNull();
        assertThat(e.version()).isEqualTo("25.0.3");
        assertThat(e.vendor()).isEqualTo(JdkVendor.TEMURIN.displayName());
        assertThat(e.accessCount()).isEqualTo(1);
    }
}
