// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The registry contract behind: a tool costs one class and one line in
 * {@link McpTools#standard()}, every {@code jk_*} the surface writes down is a tool that exists,
 * and the default {@code tools/list} is the loop set — every card an MCP host shows the model is
 * paid for on every turn.
 */
class McpToolRegistryTest {

    /** A leftover prefixed tool name. The server name scopes tools; the prefix is not part of one. */
    private static final Pattern PREFIXED = Pattern.compile("jk_[a-z_]+");

    /** The entire cost of a new tool: this class, plus one line in {@link McpTools#standard()}. */
    private static final class QuuxTool implements McpTool {

        @Override
        public Spec spec() {
            return new Spec("jk_quux", "A tool that exists only in this test.", McpSchemas.dirOnly("Where to quux"));
        }

        @Override
        public Map<String, Object> call(McpCall in) {
            return in.ok(McpEnvelope.of("quux", Map.of("dir", String.valueOf(in.dir()))), "quux");
        }
    }

    @Test
    void one_class_plus_one_registry_line_is_listed_and_callable() {
        McpTools tools = new McpTools(List.of(new QuuxTool()));
        assertThat(tools.names()).containsExactly("jk_quux");

        List<Map<String, Object>> rows = objects(tools.listing(McpTools.Surface.LOOP), "tools");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("name")).isEqualTo("jk_quux");
        assertThat(rows.getFirst().get("description")).isEqualTo("A tool that exists only in this test.");
        assertThat(rows.getFirst().get("inputSchema")).isNotNull();

        // Same registry answers tools/call — the name is not typed a second time anywhere.
        Map<String, Object> result =
                tools.call(context(), Map.of("name", "jk_quux", "arguments", Map.of("dir", "/ws")));
        Map<String, Object> structured = object(result, "structuredContent");
        assertThat(structured.get("type")).isEqualTo("quux");
        assertThat(structured.get("dir")).isEqualTo("/ws");
    }

    @Test
    void a_name_that_is_not_registered_is_a_protocol_error() {
        McpTools tools = new McpTools(List.of(new QuuxTool()));
        assertThatThrownBy(() -> tools.call(context(), Map.of("name", "jk_quuux")))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("unknown tool");
    }

    @Test
    void two_classes_cannot_claim_one_name() {
        List<McpTool> clash = List.of(new QuuxTool(), new QuuxTool());
        assertThatThrownBy(() -> new McpTools(clash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jk_quux");
    }

    @Test
    void a_loop_set_naming_an_unregistered_tool_is_rejected() {
        List<McpTool> one = List.of(new QuuxTool());
        assertThatThrownBy(() -> new McpTools(one, List.of("jk_quux", "jk_nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jk_nope");
    }

    @Test
    void the_default_list_is_the_five_loop_tools() {
        McpTools tools = McpTools.standard();
        List<String> listed = names(tools.listing(McpTools.Surface.LOOP));
        assertThat(listed).containsExactly("run", "diagnostics", "deps", "why", "skill");
        assertThat(listed).isEqualTo(tools.loopNames());
        assertThat(listed).doesNotContain("bind", "history", "manifest");
    }

    @Test
    void the_loop_cards_are_one_or_two_sentences() {
        for (Map<String, Object> row : objects(McpTools.standard().listing(McpTools.Surface.LOOP), "tools")) {
            String description = String.valueOf(row.get("description"));
            assertThat(description).as("%s card", row.get("name")).endsWith(".");
            assertThat(description.length()).as("%s card", row.get("name")).isLessThanOrEqualTo(160);
        }
    }

    @Test
    void the_full_list_is_every_registered_tool_and_nothing_twice() {
        McpTools tools = McpTools.standard();
        List<String> listed = names(tools.listing(McpTools.Surface.ALL));
        assertThat(listed).isEqualTo(tools.names());
        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed).noneMatch(n -> n.startsWith("jk_"));
        assertThat(listed).contains("why", "outdated", "history", "jdk", "bind");
        assertThat(listed).doesNotContain("jk_tools");
        assertThat(listed.size()).isGreaterThan(McpTools.LOOP.size());
    }

    @Test
    void the_surface_setting_reads_loop_or_all_and_defaults_to_loop() {
        assertThat(McpTools.Surface.of("all")).isEqualTo(McpTools.Surface.ALL);
        assertThat(McpTools.Surface.of(" ALL ")).isEqualTo(McpTools.Surface.ALL);
        assertThat(McpTools.Surface.of("loop")).isEqualTo(McpTools.Surface.LOOP);
        assertThat(McpTools.Surface.of(null)).isEqualTo(McpTools.Surface.LOOP);
        assertThat(McpTools.Surface.of("everything")).isEqualTo(McpTools.Surface.LOOP);
    }

    @Test
    void extended_true_lists_every_tool_and_a_hidden_tool_is_still_callable() {
        McpTools tools = McpTools.standard();
        assertThat(names(tools.listing(McpTools.Surface.LOOP, Map.of("extended", true))))
                .isEqualTo(tools.names());
        assertThat(names(tools.listing(McpTools.Surface.LOOP, Map.of("extended", "true"))))
                .contains("history", "bind", "outdated");
        assertThat(names(tools.listing(McpTools.Surface.ALL, Map.of("extended", false))))
                .isEqualTo(tools.names());

        Map<String, Object> result =
                tools.call(context(), Map.of("name", "outdated", "arguments", Map.of("dir", "/nowhere")));
        assertThat(object(result, "structuredContent").get("type")).isEqualTo("outdated");
        assertThatThrownBy(() -> tools.call(context(), Map.of("name", "nope")))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("unknown tool: nope");
    }

    /**
     * The default list against the comparator's: {@code bench/agent-loop/wrappers/results-mcp}
     * serves three tools over a Maven or Gradle results file, captured in
     * {@code src/test/resources/mcp/wrapper-tools-list.json}. Both are re-serialized by MiniJson
     * so the bytes compare serializer-for-serializer. Eight cards against three cannot reach the
     * wrapper's size — the JSON skeleton of a card with no description and an empty schema is
     * seventy bytes, and eight of those are more than half the wrapper's list — so the bar is
     * the ratio the loop cards achieve, and it only ratchets down.
     */
    @Test
    void the_default_list_is_within_the_wrapper_budget() throws IOException {
        String wrapper = MiniJson.write(objects(wrapperList(), "tools"));
        String loop = MiniJson.write(objects(McpTools.standard().listing(McpTools.Surface.LOOP), "tools"));
        String all = MiniJson.write(objects(McpTools.standard().listing(McpTools.Surface.ALL), "tools"));
        assertThat(wrapper.length()).isBetween(1_000, 1_600);
        String listing = MiniJson.write(McpTools.standard().listing(McpTools.Surface.LOOP));
        assertThat(listing.length())
                .as("default tools/list reply %d bytes", listing.length())
                .isLessThanOrEqualTo(1_500);
        assertThat(all.length()).as("full list is the expensive one").isGreaterThan(loop.length() * 4);
    }

    @Test
    void the_playbook_names_only_the_default_tools() {
        String instructions = McpTools.INSTRUCTIONS;
        for (String name : List.of("run", "diagnostics", "deps", "why", "skill")) {
            assertThat(instructions).contains(name);
            assertThat(McpTools.standard().loopNames()).contains(name);
        }
        assertThat(instructions).doesNotContain("jk_");
        assertThat(MiniJson.write(McpTools.standard().listing(McpTools.Surface.ALL)))
                .doesNotContain("jk_");
    }

    @Test
    void prompts_name_tools_that_exist() {
        for (String prompt : McpPrompts.names()) {
            String text = McpPrompts.playbook(prompt).orElseThrow();
            assertThat(text).doesNotContain("jk_");
            assertThat(PREFIXED.matcher(text).find()).isFalse();
        }
        assertThat(McpPrompts.playbook("learn-jumpkick").orElseThrow()).contains("skill");
        assertThat(McpPrompts.playbook("fix-failing-build").orElseThrow()).contains("run");
    }

    private static List<String> names(Map<String, Object> listing) {
        return objects(listing, "tools").stream()
                .map(r -> String.valueOf(r.get("name")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> wrapperList() throws IOException {
        try (InputStream in = McpToolRegistryTest.class.getResourceAsStream("/mcp/wrapper-tools-list.json")) {
            if (in == null) throw new AssertionError("missing test resource mcp/wrapper-tools-list.json");
            return (Map<String, Object>)
                    requireNonNull(MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    private static McpContext context() {
        EngineHttpJobs jobs = new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                return 1L;
            }

            @Override
            public boolean cancel(long jid) {
                return false;
            }

            @Override
            public int cancelDir(String dir) {
                return 0;
            }
        };
        return new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                jobs,
                dir -> Map.of(),
                List::of,
                "0",
                null,
                null,
                null,
                null);
    }
}
