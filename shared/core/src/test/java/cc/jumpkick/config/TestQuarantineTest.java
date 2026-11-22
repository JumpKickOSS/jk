// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk-quarantine.toml} and the root's {@code [test] quarantine}. */
class TestQuarantineTest {

    @TempDir
    Path root;

    @Test
    void no_file_is_no_quarantine() throws IOException {
        assertThat(TestQuarantine.read(root).entries()).isEmpty();
    }

    @Test
    void entries_round_trip_sorted_by_module_then_class() throws IOException {
        TestQuarantine q = TestQuarantine.NONE
                .with(new TestQuarantine.Entry("b.ZTest", "app", "flaky", "2026-11-22", null))
                .with(new TestQuarantine.Entry("a.YTest", "app", "", "2026-11-21", "https://example.org/issues/7"))
                .with(new TestQuarantine.Entry("c.XTest", "", "", "", null));
        q.write(root);

        String text = Files.readString(root.resolve(TestQuarantine.FILE));
        assertThat(text).startsWith("# Test classes in quarantine").contains("since = 2026-11-21\n");
        TestQuarantine back = TestQuarantine.read(root);
        assertThat(back).isEqualTo(q);
        assertThat(back.entries())
                .extracting(TestQuarantine.Entry::className)
                .containsExactly("c.XTest", "a.YTest", "b.ZTest");
        assertThat(back.classNames()).containsExactly("a.YTest", "b.ZTest", "c.XTest");
    }

    @Test
    void an_entry_holds_its_module_or_every_module_when_it_names_none() throws IOException {
        Files.writeString(root.resolve(TestQuarantine.FILE), """
                [[class]]
                name = "a.FlakyTest"
                module = "server/engine"

                [[class]]
                name = "b.AnyTest"
                """);
        TestQuarantine q = TestQuarantine.read(root);

        assertThat(q.find("server/engine", "a.FlakyTest")).isNotNull();
        assertThat(q.find("clients/cli", "a.FlakyTest")).isNull();
        assertThat(q.find("clients/cli", "b.AnyTest")).isNotNull();
        assertThat(q.find("server/engine", "a.Other")).isNull();
    }

    @Test
    void a_malformed_file_names_itself() throws IOException {
        Files.writeString(root.resolve(TestQuarantine.FILE), "[[class]]\nreason = \"no name\"\n");
        assertThatThrownBy(() -> TestQuarantine.read(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jk-quarantine.toml: a [[class]] has no name");
    }

    @Test
    void module_key_is_relative_with_forward_slashes() {
        assertThat(TestQuarantine.moduleKey(root, root)).isEqualTo(".");
        assertThat(TestQuarantine.moduleKey(root, root.resolve("server").resolve("engine")))
                .isEqualTo("server/engine");
    }

    @Test
    void policy_is_two_of_twenty_unless_the_root_says_otherwise() throws IOException {
        assertThat(TestQuarantine.Policy.of(root)).isEqualTo(TestQuarantine.Policy.DEFAULT);

        Files.writeString(root.resolve("jk.toml"), "name = \"m\"\n[test]\nquarantine = { flakes = 3, runs = 10 }\n");
        TomlScan.clearCache();
        assertThat(TestQuarantine.Policy.of(root)).isEqualTo(new TestQuarantine.Policy(3, 10));

        Files.writeString(root.resolve("jk.toml"), "name = \"m\"\n[test]\nquarantine = false\n");
        TomlScan.clearCache();
        assertThat(TestQuarantine.Policy.of(root).off()).isTrue();
    }

    private static void parse(String test) {
        JkBuildParser.parse("name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n[test]\n" + test);
    }

    @Test
    void the_manifest_takes_false_or_flakes_and_runs() {
        parse("quarantine = false\n");
        parse("quarantine = { flakes = 2, runs = 20 }\n");
        parse("quarantine = { runs = 50 }\n");
        assertThatThrownBy(() -> parse("quarantine = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].quarantine must be false or { flakes = 2, runs = 20 }");
        assertThatThrownBy(() -> parse("quarantine = { flakes = 5, runs = 3 }\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].quarantine needs 1 <= flakes <= runs");
        assertThatThrownBy(() -> parse("quarantine = { after = 2 }\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown key `after`");
    }
}
