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
    void an_inline_table_reads_as_its_dotted_keys(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                name = "widget"
                jdk  = { vendor = "corretto", version = ">=21.0.4", required = true }  # binding
                java = 21
                """);
        TomlScan scan = TomlScan.scan(toml, "jdk.vendor", "jdk.version", "jdk.required", "java");
        assertThat(scan.get("jdk.vendor")).isEqualTo("corretto");
        assertThat(scan.get("jdk.version")).isEqualTo(">=21.0.4");
        assertThat(scan.get("jdk.required")).isEqualTo("true");
        assertThat(scan.getInt("java", 0)).isEqualTo(21);
        assertThat(TomlScan.inlineTable("{ a = \"x, y\", b = 2 }"))
                .containsEntry("a", "x, y")
                .containsEntry("b", "2");
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

    @Test
    void array_lines_read_literal_strings_and_skip_trailing_comments(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                modules = ['a#b', "c\\"d"]  # was ["old"]
                multi = [
                  "x",  # not "y"
                  'z',
                ]

                [native]
                graal = "yes"
                """);
        TomlScan scan = TomlScan.scan(toml, "modules", "multi", "native.graal");
        assertThat(scan.stringArray("modules")).containsExactly("a#b", "c\"d");
        assertThat(scan.stringArray("multi")).containsExactly("x", "z");
        assertThat(scan.get("native.graal")).isEqualTo("yes");
    }

    @Test
    void lines_inside_a_multiline_string_are_text_not_keys_or_headers(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                description = \"""
                name = "decoy"
                [native]
                \"""
                name = "widget"
                notes = '''
                java = 8
                '''
                java = 25
                banner = \"""one line\"""
                """);
        TomlScan scan = TomlScan.scan(toml, "name", "java", "banner", "native.graal");
        assertThat(scan.get("name")).isEqualTo("widget");
        assertThat(scan.getInt("java", 0)).isEqualTo(25);
        assertThat(scan.get("banner")).isNull();
        assertThat(scan.hasSection("native")).isFalse();
    }

    @Test
    void an_inline_table_ends_at_its_own_closing_brace(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                jdk = { vendor = "corretto", version = "25" }  # pinned {for now}
                """);
        TomlScan scan = TomlScan.scan(toml, "jdk.vendor", "jdk.version");
        assertThat(scan.get("jdk.vendor")).isEqualTo("corretto");
        assertThat(scan.get("jdk.version")).isEqualTo("25");
        assertThat(TomlScan.inlineTable("{ a = \"}\" } # }")).containsEntry("a", "}");
        assertThat(TomlScan.inlineTable("{ a = 1, b = { c = 2 } }")).isEmpty();
    }

    @Test
    void quoted_keys_and_headers_read_as_their_bare_spelling(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                "name" = "widget"
                "a.b" = "dotted inside quotes"
                jdk = { 'vendor' = "temurin" }

                [ "native" ]
                'graal' = "yes"

                [cache."remote"]
                url = "https://cache.example"
                """);
        TomlScan scan = TomlScan.scan(toml, "name", "a.b", "jdk.vendor", "native.graal", "cache.remote.url");
        assertThat(scan.get("name")).isEqualTo("widget");
        assertThat(scan.get("a.b")).isNull();
        assertThat(scan.get("jdk.vendor")).isEqualTo("temurin");
        assertThat(scan.get("native.graal")).isEqualTo("yes");
        assertThat(scan.get("cache.remote.url")).isEqualTo("https://cache.example");
        assertThat(scan.hasSection("native")).isTrue();
    }

    @Test
    void a_nested_array_reads_as_absent_and_opens_no_section(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                matrix = [
                  ["a", "b"],
                  ["c"],
                ]
                pairs = [["x"]]
                name = "widget"
                """);
        TomlScan scan = TomlScan.scan(toml, "matrix", "pairs", "name");
        assertThat(scan.hasKey("matrix")).isFalse();
        assertThat(scan.hasKey("pairs")).isFalse();
        assertThat(scan.get("name")).isEqualTo("widget");
        assertThat(scan.hasSection("\"c\"")).isFalse();
    }

    @Test
    void an_unwanted_multiline_array_is_skipped_whole(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                matrix = [
                  ["name"],
                ]
                name = "widget"
                """);
        assertThat(TomlScan.scan(toml, "name").get("name")).isEqualTo("widget");
    }
}
