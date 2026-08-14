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
    void top_level_key_stops_at_first_match_and_survives_array_tables(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1
                jdk = "temurin-25.0.1"

                [[artifact]]
                name = "a:b"
                jdk = "decoy"
                """);
        TomlScan scan = TomlScan.scan(lock, "jdk");
        assertThat(scan.get("jdk")).isEqualTo("temurin-25.0.1");
        assertThat(scan.hasSection("artifact")).isFalse(); // early-stop: never read that far
    }

    @Test
    void missing_file_reads_as_absent(@TempDir Path tmp) {
        TomlScan scan = TomlScan.scan(tmp.resolve("nope.toml"), "jdk");
        assertThat(scan.get("jdk")).isNull();
        assertThat(scan.getInt("jdk", 7)).isEqualTo(7);
    }
}
