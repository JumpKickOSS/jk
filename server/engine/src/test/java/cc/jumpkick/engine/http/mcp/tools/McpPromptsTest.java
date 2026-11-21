// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A prompt that names a tool off the default tool list says how to list that tool's card. */
class McpPromptsTest {

    @Test
    void a_prompt_that_names_a_tool_off_the_default_list_says_how_to_list_it() {
        for (String prompt : McpPrompts.names()) {
            String text = McpPrompts.playbook(prompt).orElseThrow();
            if (!McpPrompts.offList(text).isEmpty()) {
                assertThat(text).as(prompt).contains("extended=true");
            }
        }
        assertThat(McpPrompts.playbook("stall-or-cancel").orElseThrow()).contains("extended=true");
    }
}
