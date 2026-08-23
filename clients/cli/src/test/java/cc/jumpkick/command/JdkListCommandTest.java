// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.JdkListCommand.Row;
import cc.jumpkick.command.JdkListCommand.Status;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkVendor;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkListCommandTest {

    @Test
    void marks_path_javac_as_current_and_keeps_default_on_a_different_row(@TempDir Path dir) {
        Path currentHome = dir.resolve("temurin-25.0.1");
        Path defaultHome = dir.resolve("corretto-21.0.5");
        List<JdkHit> installed = List.of(
                new JdkHit(currentHome, "25.0.1", JdkVendor.UNKNOWN, "sdkman"),
                new JdkHit(defaultHome, "21.0.5", JdkVendor.UNKNOWN, "jk"));

        List<Row> rows = JdkListCommand.buildRows(installed, defaultHome, null, "linux", "x64", currentHome, null);

        assertThat(rowFor(rows, "temurin-25.0.1").status()).isEqualTo(Status.ACTIVE);
        assertThat(rowFor(rows, "corretto-21.0.5").status()).isEqualTo(Status.DEFAULT);
    }

    @Test
    void default_is_disambiguated_by_home_when_two_installs_share_an_identifier(@TempDir Path dir) {
        // Same vendor-major identifier ("temurin-25.0.3") under two roots — only
        // the install whose home matches the recorded default is marked DEFAULT.
        Path jkHome = dir.resolve("jk/temurin-25.0.3");
        Path ideHome = dir.resolve("ide/temurin-25.0.3");
        List<JdkHit> installed = List.of(
                new JdkHit(jkHome, "25.0.3", JdkVendor.TEMURIN, "jk"),
                new JdkHit(ideHome, "25.0.3", JdkVendor.TEMURIN, "intellij"));

        List<Row> rows = JdkListCommand.buildRows(installed, jkHome, null, "linux", "x64", null, null);

        List<Row> defaults =
                rows.stream().filter(r -> r.status() == Status.DEFAULT).toList();
        assertThat(defaults).hasSize(1);
        assertThat(defaults.getFirst().location()).isEqualTo("jk");
    }

    @Test
    void current_takes_precedence_when_default_and_current_are_the_same_jdk(@TempDir Path dir) {
        Path home = dir.resolve("temurin-25.0.1");
        List<JdkHit> installed = List.of(new JdkHit(home, "25.0.1", JdkVendor.UNKNOWN, "sdkman"));

        List<Row> rows = JdkListCommand.buildRows(installed, home, null, "linux", "x64", home, null);

        assertThat(rowFor(rows, "temurin-25.0.1").status()).isEqualTo(Status.ACTIVE);
        assertThat(rows).noneMatch(r -> r.status() == Status.DEFAULT);
    }

    @Test
    void synthesizes_a_current_row_when_path_javac_is_not_in_the_list(@TempDir Path dir) throws IOException {
        Path listed = dir.resolve("corretto-21.0.5");
        Path currentHome = dir.resolve("temurin-25.0.1");
        makeJdkInstall(currentHome, "25.0.1"); // a real JDK dir on PATH but unknown to probes

        List<JdkHit> installed = List.of(new JdkHit(listed, "21.0.5", JdkVendor.UNKNOWN, "jk"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, null, "linux", "x64", currentHome, null);

        Row current = rowFor(rows, "temurin-25.0.1");
        assertThat(current.status()).isEqualTo(Status.ACTIVE);
        assertThat(current.location()).isEqualTo("path"); // synthesized rows are attributed to PATH
    }

    @Test
    void no_current_row_when_path_has_no_javac(@TempDir Path dir) {
        List<JdkHit> installed =
                List.of(new JdkHit(dir.resolve("temurin-25.0.1"), "25.0.1", JdkVendor.UNKNOWN, "sdkman"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, null, "linux", "x64", null, null);

        assertThat(rows).noneMatch(r -> r.status() == Status.ACTIVE);
    }

    @Test
    void marks_installed_patch_outdated_when_feed_has_newer_point_release(@TempDir Path dir) {
        Path home = dir.resolve("temurin-25.0.3");
        List<JdkHit> installed = List.of(new JdkHit(home, "25.0.3", JdkVendor.TEMURIN, "jk"));
        JdkCatalog catalog = catalog(
                entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.3", "temurin-25.0.3"),
                entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.4", "temurin-25.0.4"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, catalog, "linux", "x64", null, null);

        Row installedRow = rowFor(rows, "temurin-25.0.3");
        assertThat(installedRow.status()).isEqualTo(Status.OUTDATED);
        assertThat(installedRow.statusLabel()).isEqualTo("outdated!");
        // --all would surface the newer patch as available; buildRows always includes it.
        Row available = rowFor(rows, "temurin-25.0.4");
        assertThat(available.status()).isEqualTo(Status.AVAILABLE);
        assertThat(available.location()).isEqualTo("download");
    }

    @Test
    void active_outdated_composes_status_label(@TempDir Path dir) {
        Path home = dir.resolve("temurin-25.0.3");
        List<JdkHit> installed = List.of(new JdkHit(home, "25.0.3", JdkVendor.TEMURIN, "jk"));
        JdkCatalog catalog = catalog(entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.4", "temurin-25.0.4"));

        List<Row> rows = JdkListCommand.buildRows(installed, home, catalog, "linux", "x64", home, null);

        Row row = rowFor(rows, "temurin-25.0.3");
        assertThat(row.status()).isEqualTo(Status.ACTIVE);
        assertThat(row.statusLabel()).isEqualTo("active/default/outdated!");
    }

    @Test
    void up_to_date_install_suppresses_available_row_for_same_family(@TempDir Path dir) {
        Path home = dir.resolve("temurin-25.0.4");
        List<JdkHit> installed = List.of(new JdkHit(home, "25.0.4", JdkVendor.TEMURIN, "jk"));
        JdkCatalog catalog = catalog(
                entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.3", "temurin-25.0.3"),
                entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.4", "temurin-25.0.4"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, catalog, "linux", "x64", null, null);

        assertThat(rowFor(rows, "temurin-25.0.4").status()).isEqualTo(Status.INSTALLED);
        assertThat(rows).noneMatch(r -> r.spec().equals("temurin-25.0.4") && r.status() == Status.AVAILABLE);
        assertThat(rows).noneMatch(r -> r.status() == Status.AVAILABLE && r.major() == 25);
    }

    @Test
    void available_row_still_shown_for_major_with_no_install(@TempDir Path dir) {
        Path home = dir.resolve("temurin-21.0.5");
        List<JdkHit> installed = List.of(new JdkHit(home, "21.0.5", JdkVendor.TEMURIN, "jk"));
        JdkCatalog catalog = catalog(
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5", "temurin-21.0.5"),
                entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.4", "temurin-25.0.4"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, catalog, "linux", "x64", null, null);

        assertThat(rowFor(rows, "temurin-21.0.5").status()).isEqualTo(Status.INSTALLED);
        assertThat(rowFor(rows, "temurin-25.0.4").status()).isEqualTo(Status.AVAILABLE);
    }

    private static Row rowFor(List<Row> rows, String spec) {
        return rows.stream()
                .filter(r -> r.spec().equals(spec))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + spec + " in " + rows));
    }

    private static void makeJdkInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }

    private static JdkCatalog catalog(JdkCatalog.Entry... entries) {
        return new JdkCatalog(List.of(entries));
    }

    private static JdkCatalog.Entry entry(
            String vendor, String product, String suggested, int major, String version, String folder) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                suggested,
                major,
                version,
                false,
                false,
                List.of(),
                "linux",
                "x64",
                "targz",
                URI.create("https://example.invalid/" + folder + ".tar.gz"),
                "deadbeef",
                1L,
                folder,
                "");
    }
}
