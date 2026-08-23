// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        Files.writeString(file, """
                100|1|17.0.1|Eclipse|%s
                500|2|25.0.1|Amazon|%s
                300|1|21.0.5|Azul|%s
                """.formatted(old, neu, mid), StandardCharsets.UTF_8);

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
    void concurrent_touches_lose_no_rows(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve(".jk-access.log");
        int jdks = 8;
        int touchesPerJdk = 5;
        List<Path> homes = new ArrayList<>();
        for (int i = 0; i < jdks; i++) {
            Path home = tempDir.resolve("jdk-" + i);
            Files.createDirectories(home);
            homes.add(home);
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(jdks)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Path home : homes) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // Fresh instance per touch — same file, no shared state.
                    for (int t = 0; t < touchesPerJdk; t++) {
                        new JdkAccessLedger(file).touch(home, "21.0.5", "Eclipse Temurin");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        }

        var map = new JdkAccessLedger(file).byJavaHome();
        assertThat(map).hasSize(jdks);
        for (Path home : homes) {
            JdkAccessLedger.Entry e = map.get(home.toRealPath().toString());
            assertThat(e).as("row for %s", home).isNotNull();
            assertThat(e.accessCount()).isEqualTo(touchesPerJdk);
        }
    }

    @Test
    void symlinked_home_and_real_home_share_one_row(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".jk-access.log");
        Path real = Files.createDirectories(tempDir.resolve("temurin-21.0.5"));
        Path link = tempDir.resolve("temurin-21");
        DirLinks.replace(link, real);

        JdkAccessLedger ledger = new JdkAccessLedger(file);
        ledger.touch(real, "21.0.5", "Eclipse Temurin");
        ledger.touch(link, "21.0.5", "Eclipse Temurin");

        var map = ledger.byJavaHome();
        assertThat(map).hasSize(1);
        JdkAccessLedger.Entry e = map.get(real.toRealPath().toString());
        assertThat(e).isNotNull();
        assertThat(e.accessCount()).isEqualTo(2);
    }

    @Test
    void old_journal_is_folded_and_removed_on_first_touch(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".jk-access.log");
        Path old = tempDir.resolve(".access.log");
        Path existing = Files.createDirectories(tempDir.resolve("temurin-21.0.5"));
        // Old TSV journal: two events for a still-installed JDK, one for a gone JDK.
        Files.writeString(old, """
                100\tinstall\ttemurin-21.0.5
                300\tresolve\ttemurin-21.0.5
                200\tresolve\tcorretto-17.0.9
                """, StandardCharsets.UTF_8);

        Path other = Files.createDirectories(tempDir.resolve("zulu-25.0.1"));
        new JdkAccessLedger(file).touch(other, "25.0.1", "Azul Zulu");

        assertThat(Files.exists(old)).as("old journal removed").isFalse();
        var map = new JdkAccessLedger(file).byJavaHome();
        assertThat(map).hasSize(2); // folded temurin row + fresh zulu row; gone corretto dropped
        JdkAccessLedger.Entry folded = map.get(existing.toRealPath().toString());
        assertThat(folded).isNotNull();
        assertThat(folded.timestampMillis()).isEqualTo(300);
        assertThat(folded.accessCount()).isEqualTo(2);
        assertThat(folded.version()).isEqualTo("21.0.5");
    }

    @Test
    void display_name_from_feed_is_the_single_vendor_form() {
        // Raw feed strings ("Eclipse" + "Temurin") and the discovery path (JdkVendor.displayName)
        // must land the same ledger cell.
        assertThat(JdkVendor.displayNameFromFeed("Eclipse", "Temurin"))
                .isEqualTo(JdkVendor.TEMURIN.displayName())
                .isEqualTo("Eclipse Temurin");
        assertThat(JdkVendor.displayNameFromFeed("GraalVM Community", "GraalVM CE"))
                .isEqualTo(JdkVendor.GRAALVM_CE.displayName());
        // Unrecognised feeds keep their raw strings instead of collapsing to "Unknown".
        assertThat(JdkVendor.displayNameFromFeed("Acme", "SuperJDK")).isEqualTo("Acme SuperJDK");
        assertThat(JdkVendor.displayNameFromFeed("Acme", "")).isEqualTo("Acme");
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
