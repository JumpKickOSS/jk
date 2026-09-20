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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The registry contract behind: a tool costs one class and one line in
 * {@link McpTools#standard()}, every {@code jk_*} the surface writes down is a tool that exists,
 * and the default {@code tools/list} is the loop set — every card an MCP host shows the model is
 * paid for on every turn.
 */
class McpToolRegistryTest {

    /** A name written anywhere the surface publishes prose. */
    private static final Pattern TOOL_NAME = Pattern.compile("jk_[a-z_]+");

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
    void the_default_list_is_the_loop_set_plus_the_catalog_and_nothing_else() {
        McpTools tools = McpTools.standard();
        List<String> listed = names(tools.listing(McpTools.Surface.LOOP));
        List<String> expected = new ArrayList<>(McpTools.LOOP);
        expected.add(McpTools.CATALOG);
        assertThat(listed).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(listed)
                .containsExactly(
                        "jk_run",
                        "jk_results",
                        "jk_diagnostics",
                        "jk_deps",
                        "jk_manifest",
                        "jk_manual",
                        "jk_bind",
                        "jk_tools");
        assertThat(listed).isEqualTo(tools.loopNames());
    }

    @Test
    void the_loop_cards_are_one_sentence_each() {
        for (Map<String, Object> row : objects(McpTools.standard().listing(McpTools.Surface.LOOP), "tools")) {
            String description = String.valueOf(row.get("description"));
            assertThat(description)
                    .as("%s card", row.get("name"))
                    .doesNotContain(". ")
                    .endsWith(".");
            assertThat(description.length()).as("%s card", row.get("name")).isLessThanOrEqualTo(110);
        }
    }

    @Test
    void the_full_list_is_every_registered_tool_and_nothing_twice() {
        McpTools tools = McpTools.standard();
        List<String> listed = names(tools.listing(McpTools.Surface.ALL));
        assertThat(listed).isEqualTo(tools.names());
        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed).allMatch(n -> n.startsWith("jk_"));
        assertThat(listed).contains("jk_tools", "jk_why", "jk_outdated", "jk_history", "jk_jdk");
        assertThat(listed.size()).isGreaterThan(McpTools.LOOP.size() + 1);
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
    void the_catalog_lists_every_tool_outside_the_loop_with_one_liners() {
        McpTools tools = McpTools.standard();
        Map<String, Object> result = tools.call(context(), Map.of("name", "jk_tools", "arguments", Map.of()));
        Map<String, Object> structured = object(result, "structuredContent");
        assertThat(structured.get("type")).isEqualTo("tools");
        List<Map<String, Object>> rows = objects(structured, "tools");
        List<String> listed =
                rows.stream().map(r -> String.valueOf(r.get("name"))).toList();
        List<String> outsideLoop = new ArrayList<>(tools.names());
        outsideLoop.removeAll(McpTools.LOOP);
        outsideLoop.remove(McpTools.CATALOG);
        assertThat(listed).isEqualTo(outsideLoop);
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("description")))
                    .as("%s one-liner", row.get("name"))
                    .doesNotContain(". ")
                    .isNotBlank();
        }
        String text = String.valueOf(objects(result, "content").getFirst().get("text"));
        assertThat(text).contains("jk_outdated — ").contains("jk_why — ").doesNotContain("jk_run — ");
    }

    @Test
    void the_catalog_calls_a_tool_outside_the_loop_by_name() {
        McpTools tools = McpTools.standard();
        Map<String, Object> result = tools.call(
                context(),
                Map.of(
                        "name",
                        "jk_tools",
                        "arguments",
                        Map.of("action", "call", "name", "jk_outdated", "arguments", Map.of("dir", "/nowhere"))));
        Map<String, Object> structured = object(result, "structuredContent");
        assertThat(structured.get("type")).isEqualTo("outdated");
        assertThat(String.valueOf(objects(result, "content").getFirst().get("text")))
                .startsWith("outdated");

        Map<String, Object> card = objects(tools.listing(McpTools.Surface.ALL), "tools").stream()
                .filter(t -> "jk_outdated".equals(t.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(object(object(card, "inputSchema"), "properties")).containsKeys("dir", "all");

        assertThatThrownBy(() -> tools.call(
                        context(),
                        Map.of("name", "jk_tools", "arguments", Map.of("action", "call", "name", "jk_nope"))))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("unknown tool: jk_nope");
        assertThatThrownBy(() -> tools.call(
                        context(),
                        Map.of("name", "jk_tools", "arguments", Map.of("action", "call", "name", "jk_tools"))))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("cannot call itself");
        assertThatThrownBy(
                        () -> tools.call(context(), Map.of("name", "jk_tools", "arguments", Map.of("action", "call"))))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("requires name");
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
        assertThat(loop.length())
                .as("default tools/list %d bytes vs wrapper %d bytes", loop.length(), wrapper.length())
                .isLessThanOrEqualTo((int) (wrapper.length() * 1.75));
        assertThat(all.length()).as("full list is the expensive one").isGreaterThan(loop.length() * 4);
    }

    @Test
    void every_tool_the_playbook_names_exists() {
        assertThat(namesIn(McpTools.INSTRUCTIONS))
                .isNotEmpty()
                .allSatisfy(name -> assertThat(McpTools.standard().names()).contains(name));
    }

    @Test
    void the_playbook_names_only_tools_the_default_list_serves() {
        assertThat(namesIn(McpTools.INSTRUCTIONS))
                .allSatisfy(name -> assertThat(McpTools.standard().loopNames()).contains(name));
    }

    @Test
    void every_tool_a_prompt_names_exists() {
        Set<String> named = new LinkedHashSet<>();
        for (String prompt : McpPrompts.names()) {
            named.addAll(namesIn(McpPrompts.playbook(prompt).orElseThrow()));
        }
        assertThat(named)
                .isNotEmpty()
                .allSatisfy(name -> assertThat(McpTools.standard().names()).contains(name));
    }

    @Test
    void every_tool_a_description_or_schema_cross_references_exists() {
        McpTools tools = McpTools.standard();
        Set<String> mentioned = namesIn(MiniJson.write(tools.listing(McpTools.Surface.ALL)));
        assertThat(mentioned).contains("jk_build", "jk_why"); // the cross-references really are in there
        assertThat(mentioned).allSatisfy(name -> assertThat(tools.names()).contains(name));
    }

    /** The structured payload's {@code exclusions} array is named where a caller decides to call the tool. */
    @Test
    void the_why_card_names_the_exclusions_array_its_payload_carries() {
        Map<String, Object> why = objects(McpTools.standard().listing(McpTools.Surface.ALL), "tools").stream()
                .filter(row -> "jk_why".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(why.get("description"))).contains("exclusions");
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

    private static Set<String> namesIn(String prose) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOOL_NAME.matcher(prose);
        while (m.find()) out.add(m.group());
        return out;
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
