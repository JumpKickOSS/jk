// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownProseTest {

    @Test
    void code_spans_fences_link_targets_and_urls_are_blanked_and_offsets_kept() {
        String doc = """
                Run `jk build` or ``a `nested` span``; see [the guide](docs/jk.md) and https://x.dev/jk.
                <!-- jk:begin --> a <b class="jk">bold</b> tag
                [ref]: docs/jk.md

                ```bash
                jk test
                ```
                Prose after the fence says tool.
                """;
        String view = MarkdownProse.blank(doc);
        assertThat(view).hasSameSizeAs(doc);
        assertThat(view.lines().toList()).hasSameSizeAs(doc.lines().toList());
        assertThat(view)
                .doesNotContain("jk")
                .contains("Run", "the guide", " a ", "bold", "tag", "Prose after the fence says tool.");
    }

    @Test
    void a_lone_backtick_or_an_unclosed_span_stays_prose() {
        assertThat(MarkdownProse.blank("a ` tick, then jk")).isEqualTo("a ` tick, then jk");
        assertThat(MarkdownProse.blank("open `span\n\nclosed` jk")).isEqualTo("open `span\n\nclosed` jk");
    }

    @Test
    void an_unclosed_fence_runs_to_the_end_and_a_tilde_fence_is_not_closed_by_backticks() {
        assertThat(MarkdownProse.blank("~~~\njk\n```\njk")).doesNotContain("jk");
        assertThat(MarkdownProse.blank("  ```\n  jk\n  ```\njk after")).endsWith("\njk after");
    }
}
