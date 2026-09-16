// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.objects;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.JkManual;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/user/mcp.md} is the only copy of the MCP registry outside the code: it tables every
 * tool name, resource URI and prompt. A tool costs one class and one registry line
 * ({@link McpToolRegistryTest}), and nothing about that line reminds its author of the doc — so
 * without this diff the next tool ships undocumented, or the doc advertises a name the server
 * rejects, with every gate green.
 *
 * <p>Names only, as sets: the Role/description prose is authored, not derived, and row order is
 * the doc's own editorial choice. Each parsed table must be non-empty — a markdown drift that
 * parses zero rows is a red result, not a vacuous green one.
 */
class McpDocParityTest {

    /** A first-column tool cell: {@code **`jk_x`**}, repeated in combined rows. */
    private static final Pattern TOOL_CELL = Pattern.compile("\\*\\*`(jk_[a-z_]+)`\\*\\*");

    /** A first-column resource cell: {@code `jk://…`}. */
    private static final Pattern RESOURCE_CELL = Pattern.compile("`(jk://[a-z/-]+)`");

    /** A first-column prompt cell: {@code `name`}. */
    private static final Pattern PROMPT_CELL = Pattern.compile("`([a-z][a-z-]*)`");

    @Test
    void doc_tool_table_matches_the_registry() throws IOException {
        Set<String> doc = firstCellNames(section("Tools"), TOOL_CELL);
        assertThat(doc)
                .as("tool rows parsed from docs/user/mcp.md — zero means the parse rotted, not the doc")
                .isNotEmpty();
        assertThat(doc)
                .as("docs/user/mcp.md Tools table vs McpTools.standard()")
                .containsExactlyInAnyOrderElementsOf(McpTools.standard().names());
    }

    /**
     * The playbook's {@code ## MCP tools} table is the default {@code tools/list} in prose: same
     * names, same one-sentence descriptions, byte for byte. A card that says one thing while the
     * manual says another is two playbooks for one loop.
     */
    @Test
    void manual_tool_table_is_the_default_list_verbatim() {
        Map<String, String> manual = new LinkedHashMap<>();
        for (String line : manualSection("MCP tools")) {
            if (!line.startsWith("|")) continue;
            String[] cells = line.split("\\|");
            if (cells.length < 3) continue;
            Matcher m = TOOL_CELL.matcher(cells[1]);
            if (m.find()) manual.put(m.group(1), cells[2].trim());
        }
        assertThat(manual)
                .as("tool rows parsed from the playbook's MCP tools table")
                .isNotEmpty();
        McpTools tools = McpTools.standard();
        assertThat(manual.keySet())
                .as("playbook MCP tools table vs the default tools/list")
                .containsExactlyElementsOf(tools.loopNames());
        for (Map<String, Object> row : objects(tools.listing(McpTools.Surface.LOOP), "tools")) {
            String name = String.valueOf(row.get("name"));
            assertThat(manual.get(name))
                    .as("%s: playbook row vs served card", name)
                    .isEqualTo(row.get("description"));
        }
    }

    @Test
    void doc_resource_table_matches_the_registry() throws IOException {
        Set<String> doc = firstCellNames(section("Resources"), RESOURCE_CELL);
        assertThat(doc)
                .as("resource rows parsed from docs/user/mcp.md — zero means the parse rotted, not the doc")
                .isNotEmpty();
        List<String> registry = new ArrayList<>();
        List<Map<String, Object>> rows = objects(McpResources.list(), "resources");
        for (Map<String, Object> row : rows) registry.add(String.valueOf(row.get("uri")));
        assertThat(doc)
                .as("docs/user/mcp.md Resources table vs McpResources.list()")
                .containsExactlyInAnyOrderElementsOf(registry);
    }

    @Test
    void doc_prompt_table_matches_the_registry() throws IOException {
        Set<String> doc = firstCellNames(section("Prompts"), PROMPT_CELL);
        assertThat(doc)
                .as("prompt rows parsed from docs/user/mcp.md — zero means the parse rotted, not the doc")
                .isNotEmpty();
        assertThat(doc)
                .as("docs/user/mcp.md Prompts table vs McpPrompts.names()")
                .containsExactlyInAnyOrderElementsOf(McpPrompts.names());
    }

    /** Every pattern match in the FIRST cell of each table row of a section, as a sorted set. */
    private static Set<String> firstCellNames(List<String> sectionLines, Pattern cell) {
        Set<String> names = new TreeSet<>();
        for (String line : sectionLines) {
            if (!line.startsWith("|")) continue;
            String[] cells = line.split("\\|");
            if (cells.length < 2) continue;
            String first = cells[1];
            if (first.chars().allMatch(c -> c == '-' || c == ' ')) continue;
            Matcher m = cell.matcher(first);
            while (m.find()) names.add(m.group(1));
        }
        return names;
    }

    /** The lines of one {@code ## <name>} section of the served playbook, exclusive of the next heading. */
    private static List<String> manualSection(String name) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : JkManual.markdown().split("\n")) {
            if (line.startsWith("## ")) in = line.equals("## " + name);
            else if (in) out.add(line);
        }
        assertThat(out).as("section `## %s` in the playbook", name).isNotEmpty();
        return out;
    }

    /** The lines of one {@code ## <name>} section of the doc, exclusive of the next heading. */
    private static List<String> section(String name) throws IOException {
        List<String> lines =
                Files.readAllLines(RepoRoot.file(McpDocParityTest.class, "docs/user/mcp.md"), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : lines) {
            if (line.startsWith("## ")) in = line.equals("## " + name);
            else if (in) out.add(line);
        }
        assertThat(out).as("section `## %s` in docs/user/mcp.md", name).isNotEmpty();
        return out;
    }
}
