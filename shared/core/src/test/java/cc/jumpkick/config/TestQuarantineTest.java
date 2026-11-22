// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code .jk/test/quarantine.toml} and the root's {@code [test] quarantine}. */
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
                .with(new TestQuarantine.Entry(
                        "a.YTest",
                        "app",
                        "",
                        "2026-11-21",
                        new TestQuarantine.Issue("SHOP-7", "https://example.org/issues/7")))
                .with(new TestQuarantine.Entry("c.XTest", "", "", "", null));
        q.write(root);

        assertThat(TestQuarantine.file(root)).isEqualTo(root.resolve(".jk/test/quarantine.toml"));
        String text = Files.readString(TestQuarantine.file(root));
        assertThat(text)
                .startsWith("# Test classes in quarantine")
                .contains("# JumpKick adds a class")
                .contains("since = 2026-11-21\n");
        TestQuarantine back = TestQuarantine.read(root);
        assertThat(back).isEqualTo(q);
        assertThat(back.entries())
                .extracting(TestQuarantine.Entry::className)
                .containsExactly("c.XTest", "a.YTest", "b.ZTest");
        assertThat(back.classNames()).containsExactly("a.YTest", "b.ZTest", "c.XTest");
    }

    @Test
    void an_entry_holds_its_module_or_every_module_when_it_names_none() throws IOException {
        Files.createDirectories(TestQuarantine.file(root).getParent());
        Files.writeString(TestQuarantine.file(root), """
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
        Files.createDirectories(TestQuarantine.file(root).getParent());
        Files.writeString(TestQuarantine.file(root), "[[class]]\nreason = \"no name\"\n");
        assertThatThrownBy(() -> TestQuarantine.read(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(".jk/test/quarantine.toml: a [[class]] has no name");
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

    @Test
    void release_takes_a_class_out_of_every_module_by_its_full_or_simple_name() throws IOException {
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "", "", null))
                .with(new TestQuarantine.Entry("a.FlakyTest", "lib", "", "", null))
                .with(new TestQuarantine.Entry("b.OtherTest", "app", "", "", null))
                .write(root);

        assertThat(TestQuarantine.release(root.resolve("state"), root, "FlakyTest"))
                .extracting(TestQuarantine.Entry::module)
                .containsExactly("app", "lib");
        assertThat(TestQuarantine.read(root).classNames()).containsExactly("b.OtherTest");
        assertThat(TestQuarantine.release(root.resolve("state"), root, "a.FlakyTest"))
                .as("released already")
                .isEmpty();
        assertThat(TestQuarantine.release(root.resolve("state"), root, "ther.OtherTest"))
                .as("only a whole name part")
                .isEmpty();

        assertThat(TestQuarantine.release(root.resolve("state"), root, "b.OtherTest"))
                .hasSize(1);
        assertThat(TestQuarantine.file(root))
                .as("the last release deletes the file")
                .doesNotExist();
    }

    @Test
    void a_simple_name_two_classes_share_releases_neither() throws IOException {
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "", "", null))
                .with(new TestQuarantine.Entry("b.FlakyTest", "app", "", "", null))
                .write(root);

        assertThatThrownBy(() -> TestQuarantine.release(root.resolve("state"), root, "FlakyTest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FlakyTest names several classes: a.FlakyTest, b.FlakyTest");
        assertThat(TestQuarantine.read(root).entries()).hasSize(2);
    }

    @Test
    void one_value_is_an_id_or_a_url_and_two_are_the_id_then_the_url() {
        assertThat(TestQuarantine.Issue.of(List.of("PROJ-88"))).isEqualTo(new TestQuarantine.Issue("PROJ-88", null));
        assertThat(TestQuarantine.Issue.of(List.of("#88"))).isEqualTo(new TestQuarantine.Issue("#88", null));
        assertThat(TestQuarantine.Issue.of(List.of("https://linear.app/acme/issue/ENG-4")))
                .isEqualTo(new TestQuarantine.Issue(null, "https://linear.app/acme/issue/ENG-4"));
        assertThat(TestQuarantine.Issue.of(List.of("88", "https://github.com/acme/shop/issues/88")))
                .isEqualTo(new TestQuarantine.Issue("88", "https://github.com/acme/shop/issues/88"));
        assertThatThrownBy(() -> TestQuarantine.Issue.of(List.of("https://x/1", "PROJ-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a URL: PROJ-1; give the id, then the URL");
        assertThatThrownBy(() -> TestQuarantine.Issue.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestQuarantine.Issue.of(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_file_keeps_an_issue_s_id_and_url_as_given() throws IOException {
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.IdTest", "app", "", "", new TestQuarantine.Issue("PROJ-1", null)))
                .with(new TestQuarantine.Entry(
                        "b.UrlTest", "app", "", "", new TestQuarantine.Issue(null, "https://x/2")))
                .with(new TestQuarantine.Entry(
                        "c.BothTest", "app", "", "", new TestQuarantine.Issue("PROJ-3", "https://x/3#c")))
                .write(root);

        String text = Files.readString(TestQuarantine.file(root));
        assertThat(text)
                .contains("name = \"a.IdTest\"\nmodule = \"app\"\nissue = \"PROJ-1\"\n")
                .contains("name = \"b.UrlTest\"\nmodule = \"app\"\nissue-url = \"https://x/2\"\n")
                .contains("issue = \"PROJ-3\"\nissue-url = \"https://x/3#c\"\n");
        assertThat(TestQuarantine.read(root).entries())
                .extracting(TestQuarantine.Entry::issue)
                .containsExactly(
                        new TestQuarantine.Issue("PROJ-1", null),
                        new TestQuarantine.Issue(null, "https://x/2"),
                        new TestQuarantine.Issue("PROJ-3", "https://x/3#c"));
    }

    @Test
    void issue_tracks_a_class_in_every_module_and_replaces_what_it_had() throws IOException {
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "flaky", "2026-11-20", null))
                .with(new TestQuarantine.Entry(
                        "a.FlakyTest", "lib", "", "", new TestQuarantine.Issue("OLD-1", "https://x/1")))
                .with(new TestQuarantine.Entry("b.OtherTest", "app", "", "", null))
                .write(root);
        TestQuarantine.Issue issue = new TestQuarantine.Issue("ENG-4", null);

        assertThat(TestQuarantine.issue(root, "FlakyTest", issue))
                .extracting(e -> e.module() + " " + e.issue())
                .containsExactly("app " + issue, "lib " + issue);
        TestQuarantine back = TestQuarantine.read(root);
        assertThat(back.find("app", "a.FlakyTest"))
                .isEqualTo(new TestQuarantine.Entry("a.FlakyTest", "app", "flaky", "2026-11-20", issue));
        assertThat(back.find("lib", "a.FlakyTest"))
                .extracting(TestQuarantine.Entry::issue)
                .isEqualTo(issue);
        assertThat(back.find("app", "b.OtherTest"))
                .extracting(TestQuarantine.Entry::issue)
                .isNull();
        assertThat(TestQuarantine.issue(root, "c.GoneTest", issue))
                .as("not quarantined")
                .isEmpty();
    }

    @Test
    void a_simple_name_two_classes_share_tracks_neither() throws IOException {
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "", "", null))
                .with(new TestQuarantine.Entry("b.FlakyTest", "app", "", "", null))
                .write(root);

        assertThatThrownBy(() -> TestQuarantine.issue(root, "FlakyTest", new TestQuarantine.Issue("X-1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FlakyTest names several classes: a.FlakyTest, b.FlakyTest");
        assertThat(TestQuarantine.read(root).entries())
                .extracting(TestQuarantine.Entry::issue)
                .containsOnlyNulls();
    }
}
