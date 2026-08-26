// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomlScanTest {

    @Test
    void reads_scoped_scalars_and_section_presence(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                # comment
                name     = "widget"
                jdk      = "corretto-25"   # trailing comment
                java     = 25

                [native]
                always = true

                [dependencies]
                jdk = "not-the-project-one"
                """);
        TomlScan scan = TomlScan.scan(toml, "jdk", "java", "native.graal");
        assertThat(scan.get("jdk")).isEqualTo("corretto-25");
        assertThat(scan.getInt("java", 0)).isEqualTo(25);
        assertThat(scan.get("native.graal")).isNull();
        assertThat(scan.hasSection("native")).isTrue();
        assertThat(scan.hasSection("spring-boot")).isFalse();
    }

    @Test
    void nested_jdk_pin_stops_early_before_array_tables(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1

                [jdk]
                vendor = "temurin"
                version = "25.0.1"

                [[artifact]]
                name = "a:b"
                vendor = "decoy"
                """);
        TomlScan scan = TomlScan.scan(lock, "jdk.vendor", "jdk.version");
        assertThat(scan.get("jdk.vendor")).isEqualTo("temurin");
        assertThat(scan.get("jdk.version")).isEqualTo("25.0.1");
        assertThat(scan.hasSection("artifact")).isFalse(); // early-stop: never read that far
    }

    @Test
    void missing_optional_table_does_not_scan_array_tables(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1

                [jdk]
                vendor = "temurin"
                version = "25.0.1"

                [[artifact]]
                name = "a:b"
                vendor = "decoy"
                """);
        TomlScan scan = TomlScan.scanScalarHead(lock, "jdk.vendor", "jdk.version", "graal.vendor", "graal.version");
        assertThat(scan.get("jdk.vendor")).isEqualTo("temurin");
        assertThat(scan.get("graal.vendor")).isNull();
        // Header is seen so we can stop; keys inside the array table are not read.
        assertThat(scan.hasSection("artifact")).isTrue();
        assertThat(scan.get("artifact.vendor")).isNull();
    }

    @Test
    void section_after_an_array_of_tables_still_reads(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                [[train.profile]]
                name = "hot"
                modules = "decoy"

                [workspace]
                modules = ["app", "lib"]
                """);
        // TOML imposes no section ordering: the full scan reads past [[…]] tables,
        // and their per-element keys never satisfy a flat scalar lookup.
        TomlScan scan = TomlScan.scan(toml, "workspace.modules", "train.profile.name");
        assertThat(scan.stringArray("workspace.modules")).containsExactly("app", "lib");
        assertThat(scan.get("train.profile.name")).isNull();
        assertThat(scan.hasSection("workspace")).isTrue();
    }

    @Test
    void missing_file_reads_as_absent(@TempDir Path tmp) {
        TomlScan scan = TomlScan.scan(tmp.resolve("nope.toml"), "jdk");
        assertThat(scan.get("jdk")).isNull();
        assertThat(scan.getInt("jdk", 7)).isEqualTo(7);
    }
}
