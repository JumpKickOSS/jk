// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.SlowestTests;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What the run's episode saved is the last section of {@code jk-results.md}. */
class JkResultsReceiptTest {

    private static final String RECEIPT =
            "## Saved in this episode\n\nFix rounding · 2 runs\n\n- Code questions answered: 3\n";

    private static final BuildRecord RUN = BuildRecord.running(
                    7, "test", "/ws/app", "g:app", null, 1_700_000_000_000L, "9.9", "mcp")
            .abandoned(1_700_000_001_000L, "9.9");

    private static String render(@Nullable String receipt) {
        return JkResultsMarkdown.render(RUN, null, null, List.of(), null, SlowestTests.Ranking.EMPTY, receipt);
    }

    @Test
    void the_receipt_ends_the_report_after_a_blank_line() {
        assertThat(render(RECEIPT)).endsWith("\n\n" + RECEIPT);
        assertThat(render(null)).doesNotContain("## Saved");
    }

    @Test
    void the_header_tokens_count_the_receipt() {
        String md = render(RECEIPT);
        int at = md.indexOf(JkResultsTokens.LABEL);
        String line = md.substring(at, md.indexOf('\n', at) + 1);
        assertThat(line).isEqualTo(JkResultsTokens.line(md.length() - line.length()));
    }
}
