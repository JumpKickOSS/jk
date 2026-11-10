// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocsCheckTest {

    private static final String RULES = """
            [guards.no-ids-in-docs]
            kind = "text"
            files = ["**/*.java", "**/*.md"]
            pattern = 'TKT-[0-9]+'
            hit = "see TKT-12"
            miss = "see the ticket"
            instead = "say what it does"
            why = "the reader has no tracker"
            [guards.code-only]
            kind = "text"
            files = ["**/*.java"]
            pattern = "zzz"
            hit = "zzz"
            instead = "i"
            why = "w"
            """;

    @Test
    void only_the_rules_that_read_markdown_run_and_a_violation_in_a_doc_is_red(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        Files.writeString(root.resolve("README.md"), "# Readme\n\nFixed in TKT-41.\n");
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();

        assertThat(DocsCheck.rules(load.rules())).extracting(Rule::id).containsExactly("no-ids-in-docs");
        DocsCheck.Result r = DocsCheck.check(root, load.rules(), Baseline.EMPTY);
        assertThat(r.failures()).isEqualTo(1);
        assertThat(r.rules()).isEqualTo(1);
        assertThat(r.text())
                .contains("GUARD no-ids-in-docs", "README.md:3")
                .endsWith("\nFAIL guard docs · 1 rules · 1 red");

        Files.writeString(root.resolve("README.md"), "# Readme\n\nFixed upstream.\n");
        assertThat(DocsCheck.check(root, load.rules(), Baseline.EMPTY).text()).isEqualTo("OK guard docs · 1 rules");
    }

    @Test
    void the_prose_view_of_markdown_reads_prose_and_skips_code(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.product-name]
                kind = "text"
                files = ["**/*.md"]
                languages = ["prose"]
                blank = "prose"
                pattern = '\\bwidget\\b'
                hit = "the widget is fast"
                miss = "run `widget build`"
                instead = "Widgetron"
                why = "the product has a name"
                """);
        Files.writeString(
                root.resolve("README.md"),
                "# Readme\n\nRun `widget build`.\n\n```\nwidget test\n```\n\nSee [docs](widget/a.md).\n");
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        assertThat(DocsCheck.check(root, load.rules(), Baseline.EMPTY).text()).isEqualTo("OK guard docs · 1 rules");

        Files.writeString(root.resolve("README.md"), "# Readme\n\n```\nwidget\n```\nThe widget keeps 186 MiB.\n");
        assertThat(DocsCheck.check(root, load.rules(), Baseline.EMPTY).text())
                .contains("GUARD product-name", "README.md:6");
    }

    @Test
    void the_code_view_reads_markdown_whole_and_the_prose_view_reads_a_source_files_comments(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.comments-only]
                kind = "text"
                files = ["**/*.md", "**/*.java"]
                blank = "code"
                pattern = 'widget'
                hit = "// widget"
                instead = "i"
                why = "w"
                [guards.prose]
                kind = "text"
                files = ["**/*.md", "**/*.java"]
                blank = "prose"
                pattern = 'widget'
                hit = "// widget"
                miss = "String s = \\"widget\\";"
                instead = "i"
                why = "w"
                """);
        Files.writeString(root.resolve("README.md"), "# Readme\n\nRun `widget build`.\n");
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        String text = DocsCheck.check(root, load.rules(), Baseline.EMPTY).text();
        assertThat(text).as("code reads Markdown as written, code span and all").contains("GUARD comments-only");
        // prose's hit and miss are read as Java: the comment bites and the literal does not, or the
        // rule would come back scanner-failed.
        assertThat(text)
                .as("prose skips the span, and reads a Java comment but not a literal")
                .doesNotContain("GUARD prose");
    }
}
