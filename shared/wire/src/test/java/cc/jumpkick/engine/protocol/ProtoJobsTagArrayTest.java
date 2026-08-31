// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tag arrays survive a round trip through the wire.
 *
 * <p>The decoder used to find an array's closing bracket with {@code indexOf(']')} — the first one
 * in the document, not the first one outside a quoted element. Any value containing {@code ]}
 * truncated the array, so a tag was silently corrupted and stopped matching: the excluded tests ran.
 */
class ProtoJobsTagArrayTest {

    @Test
    void a_tag_containing_a_closing_bracket_survives_the_round_trip() {
        TestSelection sent = TestSelection.of(List.of(), false, List.of(), List.of("[slow]"));

        String json = "{" + ProtoJobs.testSelectionFields(sent) + "}";
        TestSelection back = ProtoJobs.testSelectionOf(json);

        assertThat(back.excludeTags()).containsExactly("[slow]");
    }

    @Test
    void brackets_anywhere_in_the_value_do_not_truncate_the_array() {
        TestSelection sent = TestSelection.of(List.of(), false, List.of("a]b", "[project]"), List.of("]", "x", "[y]z"));

        TestSelection back = ProtoJobs.testSelectionOf("{" + ProtoJobs.testSelectionFields(sent) + "}");

        assertThat(back.includeTags()).containsExactly("a]b", "[project]");
        assertThat(back.excludeTags()).containsExactly("]", "x", "[y]z");
    }

    @Test
    void a_pretty_printed_request_still_decodes() {
        // MCP and hand-written requests are not compact. A reader that only accepts `"k":[`
        // returns empty for `"k": [`, which reads as "the caller passed no tags".
        String pretty = "{\n  \"suites\": [],\n  \"allSuites\": false,\n"
                + "  \"includeTags\": [\"fast\"],\n  \"excludeTags\": [\"slow\"],\n"
                + "  \"tagsResolved\": false\n}";

        TestSelection back = ProtoJobs.testSelectionOf(pretty);

        assertThat(back.includeTags()).containsExactly("fast");
        assertThat(back.excludeTags()).containsExactly("slow");
        assertThat(back.gate()).isFalse();
    }

    @Test
    void gate_survives_the_round_trip() {
        TestSelection sent = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), true, true);
        TestSelection back = ProtoJobs.testSelectionOf("{" + ProtoJobs.testSelectionFields(sent) + "}");
        assertThat(back.gate()).isTrue();
        assertThat(back.suites()).containsExactly("test", "integration");
        assertThat(back.identityToken()).isEqualTo(sent.identityToken());
    }

    @Test
    void scripts_flags_survive_the_round_trip() {
        TestSelection sent = TestSelection.of(List.of(), false, List.of(), List.of(), false, false, true, false);
        TestSelection back = ProtoJobs.testSelectionOf("{" + ProtoJobs.testSelectionFields(sent) + "}");
        assertThat(back.scriptsOnly()).isTrue();
        assertThat(back.noScripts()).isFalse();
        assertThat(back.runGateScripts()).isTrue();

        TestSelection skip =
                TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true, false, true);
        TestSelection skipBack = ProtoJobs.testSelectionOf("{" + ProtoJobs.testSelectionFields(skip) + "}");
        assertThat(skipBack.gate()).isTrue();
        assertThat(skipBack.noScripts()).isTrue();
        assertThat(skipBack.runGateScripts()).isFalse();
    }
}
