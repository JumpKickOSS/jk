// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The registry contract behind: a tool costs one class and one line in
 * {@link McpTools#standard()}, and every {@code jk_*} the surface writes down is a tool that
 * exists. The old shape typed each name twice in two unlinked switches; nothing checked them.
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

        List<Map<String, Object>> rows = objects(tools.listing(), "tools");
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
    void tools_list_advertises_exactly_the_registry_and_nothing_twice() {
        McpTools tools = McpTools.standard();
        List<Map<String, Object>> rows = objects(tools.listing(), "tools");
        List<String> listed =
                rows.stream().map(r -> String.valueOf(r.get("name"))).toList();
        assertThat(listed).isEqualTo(tools.names());
        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed).allMatch(n -> n.startsWith("jk_"));
    }

    @Test
    void every_tool_the_playbook_names_exists() {
        assertThat(namesIn(McpTools.INSTRUCTIONS))
                .isNotEmpty()
                .allSatisfy(name -> assertThat(McpTools.standard().names()).contains(name));
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
        Set<String> mentioned = namesIn(MiniJson.write(tools.listing()));
        assertThat(mentioned).contains("jk_build", "jk_why"); // the cross-references really are in there
        assertThat(mentioned).allSatisfy(name -> assertThat(tools.names()).contains(name));
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
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 0, 1, 1L),
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
