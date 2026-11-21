// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RetiredKeys;
import cc.jumpkick.docs.AgentHook;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.docs.AgentsGuide;
import cc.jumpkick.docs.JkSkill;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.http.mcp.tools.McpTools;
import cc.jumpkick.engine.verbs.InertVerbHost;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an agent reads before its first call names the MCP tools it can call: every tool the
 * {@code AGENTS.md} section, the server instructions, the per-agent rule files and the agent hook
 * name is on the default {@code tools/list} (the only one most clients ever ask for), and every
 * {@code run(…)} they spell takes arguments and a {@code kind} the server accepts. The section's
 * table names every default tool, so a tool added to the loop is taught or this fails. The skill
 * pages and every tool card name no retired manifest key ({@link RetiredKeys}).
 */
class McpSteeringTest {

    /** A backticked call or bare tool name: {@code `run(kind=build)`}, {@code `deps`}. */
    private static final Pattern TICKED = Pattern.compile("`([a-z_]+)(\\(([^`)]*)\\))?`");

    /** A call in running prose: {@code run(dir=<project>)}, {@code callers(address)}. */
    private static final Pattern CALL = Pattern.compile("\\b([a-z_]+)\\(([^)]*)\\)");

    private static final McpTools TOOLS = McpTools.standard();

    @Test
    void the_agents_md_table_names_every_default_tool_and_only_default_tools() {
        for (String section : List.of(AgentsGuide.SECTION, AgentsGuide.ENFORCED_SECTION)) {
            Map<String, List<String>> calls = mcpColumn(section);
            assertThat(calls.keySet())
                    .as("MCP column of the AGENTS.md table vs the default tools/list")
                    .containsExactlyInAnyOrderElementsOf(TOOLS.loopNames());
            calls.forEach(McpSteeringTest::assertCallable);
        }
    }

    @Test
    void the_server_instructions_name_only_default_tools_with_accepted_arguments() {
        for (String text : List.of(McpTools.INSTRUCTIONS, McpTools.PINNED_INSTRUCTIONS)) {
            Map<String, List<String>> calls = calls(text, CALL);
            assertThat(calls).as("calls in the server instructions").isNotEmpty();
            calls.forEach(McpSteeringTest::assertCallable);
        }
    }

    @Test
    void the_files_jk_agents_writes_and_the_hook_name_only_default_tools(@TempDir Path dir) throws IOException {
        StringBuilder text = new StringBuilder();
        for (AgentSetup.Change c :
                AgentSetup.install(dir, AgentSetup.Mode.ALL, Set.of()).changes()) {
            // AGENTS.md is the section the table test reads; its prose names a host `node`, not the tool.
            boolean guide = c.file().getFileName().toString().equals(AgentsGuide.FILE);
            if (!guide && Files.isRegularFile(c.file()))
                text.append(Files.readString(c.file(), StandardCharsets.UTF_8));
        }
        text.append(AgentHook.decide("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"mvn test\"}}"));
        Map<String, List<String>> named = new LinkedHashMap<>();
        calls(text.toString(), TICKED).forEach((name, args) -> {
            if (TOOLS.tool(name) != null) named.put(name, args);
        });
        assertThat(named).as("MCP tools the agent files and the hook name").containsKeys("run", "callers", "rename");
        named.forEach(McpSteeringTest::assertCallable);
    }

    /**
     * The skill pages may name a tool off the default list (marked so), but every call they spell is
     * a registered tool taking those arguments, so a renamed argument cannot outlive its field.
     */
    @Test
    void the_skill_pages_spell_only_registered_tools_with_accepted_arguments() {
        Map<String, List<String>> calls = new LinkedHashMap<>();
        for (String page : skillPages()) {
            Matcher m = TICKED.matcher(page);
            while (m.find()) {
                if (m.group(2) == null && TOOLS.tool(m.group(1)) == null) continue;
                List<String> args = calls.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                if (m.group(3) != null && !m.group(3).isBlank()) args.add(m.group(3));
            }
        }
        assertThat(calls).as("tool calls in the skill").containsKeys("run", "deps", "callers", "rename");
        calls.forEach(McpSteeringTest::assertAccepted);
        for (String page : skillPages()) {
            Matcher on = ARGUMENT_ON.matcher(page);
            while (on.find()) {
                assertThat(properties(on.group(2)))
                        .as("`%s` on `%s` is an argument of its card", on.group(1), on.group(2))
                        .containsKey(on.group(1));
            }
        }
    }

    /** An argument named in prose: {@code `modules` on `run`}. */
    private static final Pattern ARGUMENT_ON = Pattern.compile("`([a-z_]+)` on `([a-z_]+)`");

    /** A key the parser refuses must not be taught by any text an agent reads. */
    @Test
    void no_agent_text_names_a_retired_manifest_key() {
        List<String> texts = new ArrayList<>(skillPages());
        texts.add(AgentsGuide.SECTION);
        texts.add(AgentsGuide.ENFORCED_SECTION);
        texts.add(McpTools.INSTRUCTIONS);
        texts.add(MiniJson.write(TOOLS.listing(McpTools.Surface.ALL)));
        for (RetiredKeys.Retired r : RetiredKeys.ALL) {
            List<String> spellings = r.key() == null
                    ? List.of("[" + r.table() + "]")
                    : List.of("`" + r.key() + "`", r.key() + " =", r.key() + "=");
            for (String text : texts) {
                for (String spelling : spellings) {
                    assertThat(text).as("retired %s, as %s", r.name(), spelling).doesNotContain(spelling);
                }
            }
        }
    }

    private static List<String> skillPages() {
        List<String> pages = new ArrayList<>();
        pages.add(JkSkill.core());
        for (String topic : JkSkill.TOPICS) pages.add(requireNonNull(JkSkill.topic(topic), topic));
        return pages;
    }

    /** {@code name} is on the default list, and each {@code run} argument list is one the server takes. */
    static void assertCallable(String name, List<String> argLists) {
        assertThat(TOOLS.loopNames())
                .as("`%s` is on the default tools/list", name)
                .contains(name);
        assertAccepted(name, argLists);
    }

    /** {@code name} is a registered tool and each argument list names its arguments and a hosted kind. */
    static void assertAccepted(String name, List<String> argLists) {
        Map<String, Object> props = properties(name);
        for (String args : argLists) {
            for (String arg : args.split(",\\s*")) {
                if (arg.isBlank() || !arg.contains("=")) continue;
                String key = arg.substring(0, arg.indexOf('=')).strip();
                String value = arg.substring(arg.indexOf('=') + 1).strip();
                assertThat(props)
                        .as("`%s(%s)`: %s is an argument of its card", name, args, key)
                        .containsKey(key);
                if (name.equals("run") && key.equals("kind")) {
                    assertThat(KINDS)
                            .as("`run(kind=%s)` is a hosted job kind", value)
                            .contains(value);
                }
            }
        }
    }

    /** The job kinds the engine hosts: what {@code run(kind=…)} accepts. */
    static final Set<String> KINDS = kinds();

    private static Set<String> kinds() {
        Set<String> out = new LinkedHashSet<>();
        VerbRegistry.standard(new InertVerbHost(), new DecisionQueue()).all().forEach(v -> out.addAll(v.jobKinds()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(String name) {
        McpTool tool = requireNonNull(TOOLS.tool(name), name);
        Object props = tool.spec().inputSchema().get("properties");
        return props instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Each tool the third column of {@code section}'s table names, with the argument lists it shows. */
    private static Map<String, List<String>> mcpColumn(String section) {
        StringBuilder column = new StringBuilder();
        for (String line : section.split("\n")) {
            if (!line.startsWith("|")) continue;
            String[] cells = line.replace("\\|", "/").split("\\|");
            if (cells.length > 3) column.append(cells[2]).append('\n');
        }
        assertThat(column).as("the AGENTS.md section's table").isNotEmpty();
        return calls(column.toString(), TICKED);
    }

    private static Map<String, List<String>> calls(String text, Pattern pattern) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            List<String> args = out.computeIfAbsent(m.group(1), k -> new ArrayList<>());
            String list = m.groupCount() == 3 ? m.group(3) : m.group(2);
            if (list != null && !list.isBlank()) args.add(list);
        }
        return out;
    }
}
