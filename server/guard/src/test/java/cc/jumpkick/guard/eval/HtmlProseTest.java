// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlProseTest {

    @Test
    void tags_attributes_code_scripts_comments_and_urls_are_blanked_and_offsets_kept() {
        String page = """
                <!doctype html>
                <html><head><title>JumpKick</title>
                <style>.jk { color: red }</style>
                <script>if (a < b) jk();</script></head>
                <body class="jk">
                <!-- jk:begin -->
                <p>Install <code>jk</code> with <kbd>jk new</kbd>, then <a href="https://x.dev/jk">read</a>.</p>
                <pre><code>jk build
                jk test</code></pre>
                <img src="jk-logo.svg" alt="jk"/>
                <svg viewBox="0 0 1 1"><text>jk</text></svg>
                <p title='a > jk'>Prose after says tool, see https://x.dev/jk.</p>
                </body></html>
                """;
        String view = HtmlProse.blank(page);
        assertThat(view).hasSameSizeAs(page);
        assertThat(view.lines().toList()).hasSameSizeAs(page.lines().toList());
        assertThat(view)
                .doesNotContain("jk", "<", ">")
                .contains("JumpKick", "Install", " with ", ", then", "read", "Prose after says tool, see");
    }

    @Test
    void an_aria_hidden_element_is_blanked_whole_with_the_elements_nested_in_it() {
        String page = """
                <div class="viz" aria-hidden="true">
                  <div class="title">jk web</div>
                  <div class="term"><span>$</span> jk code usages</div>
                </div>
                <div>Then jk answers.</div>
                """;
        String view = HtmlProse.blank(page);
        assertThat(view.lines().toList()).hasSameSizeAs(page.lines().toList());
        assertThat(view.strip()).isEqualTo("Then jk answers.");
    }

    @Test
    void a_lone_angle_bracket_stays_prose_and_an_unclosed_element_runs_to_the_end() {
        assertThat(HtmlProse.blank("a < b, then jk")).isEqualTo("a < b, then jk");
        assertThat(HtmlProse.blank("<pre>jk\n<p>jk")).isBlank();
        assertThat(HtmlProse.blank("<div aria-hidden=\"false\">jk</div>").strip())
                .isEqualTo("jk");
    }
}
