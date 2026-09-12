// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class TrustCommandTest {

    @Test
    void add_list_remove_flow(@TempDir Path state) {
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "https://github.com/acme/"))
                .isEqualTo(0);
        String listed = Capture.stdout(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("https://github.com/acme/");
        // `jk trust list` is human output: both the populated and the empty branch get the envelope.
        assertThat(listed).startsWith("\n");

        assertThat(Jk.execute("trust", "remove", "--state-dir", state.toString(), "https://github.com/acme/"))
                .isEqualTo(0);
        String empty = Capture.stdout(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(empty).contains("No trusted sources");
    }

    @Test
    void add_rejects_non_urls(@TempDir Path state) {
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "not-a-url"))
                .isEqualTo(64);
    }

    @Test
    void remove_of_unknown_prefix_is_a_usage_error(@TempDir Path state) {
        assertThat(Jk.execute("trust", "remove", "--state-dir", state.toString(), "https://nope.dev/"))
                .isEqualTo(64);
    }

    @Test
    void import_jbang_reads_the_json_list(@TempDir Path tmp) throws Exception {
        Path json = tmp.resolve("trusted-sources.json");
        Files.writeString(json, """
                [
                  // comment line
                  "https://github.com/jbangdev/",
                  "https://gist.github.com/max/"
                ]
                """);
        Path state = tmp.resolve("state");
        int exit = Jk.execute("trust", "import", "--jbang", "--file", json.toString(), "--state-dir", state.toString());
        assertThat(exit).isEqualTo(0);
        String listed = Capture.stdout(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("https://github.com/jbangdev/").contains("https://gist.github.com/max/");
    }

    @Test
    void import_without_jbang_flag_is_a_usage_error(@TempDir Path state) {
        assertThat(Jk.execute("trust", "import", "--state-dir", state.toString()))
                .isEqualTo(64);
    }

    @Test
    void add_rejects_a_prefix_without_a_host_or_with_a_dot_segment(@TempDir Path state) {
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "https:///x/"))
                .isEqualTo(64);
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "https://github.com/acme/../"))
                .isEqualTo(64);
        String listed = Capture.stdout(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("No trusted sources");
    }

    @Test
    void add_stores_a_bare_host_as_that_host_with_a_trailing_slash(@TempDir Path state) {
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "https://GitHub.com"))
                .isEqualTo(0);
        String listed = Capture.stdout(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("https://github.com/").doesNotContain("GitHub");
    }

    @Test
    void import_jbang_skips_entries_that_are_not_url_prefixes(@TempDir Path tmp) throws Exception {
        Path json = tmp.resolve("trusted-sources.json");
        Files.writeString(json, """
                [
                  "https://github.com/jbangdev/",
                  "gist.github.com/no-scheme/"
                ]
                """);
        Path state = tmp.resolve("state");
        int exit = Jk.execute("trust", "import", "--jbang", "--file", json.toString(), "--state-dir", state.toString());
        assertThat(exit).isEqualTo(0);
        String listed = Capture.stdout(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("https://github.com/jbangdev/").doesNotContain("no-scheme");
    }
}
