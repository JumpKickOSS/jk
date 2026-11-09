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
    void the_code_view_of_markdown_reads_prose_and_skips_code(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.product-name]
                kind = "text"
                files = ["**/*.md"]
                languages = ["prose"]
                blank = "code"
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
}
