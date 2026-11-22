// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.tools.McpTools;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every argument a tool reads is in its {@code inputSchema}: a host that filters arguments to the
 * schema drops the rest silently. Read from each tool's source and the helpers it hands its call to.
 */
class McpSchemaReadsTest {

    /** {@code in.str("x")}, {@code in.count("x", …)} and the other argument readers of {@link McpCall}. */
    private static final Pattern READ =
            Pattern.compile("\\bin\\.(?:str|num|intOrNull|count|strings|tri|flag|flagOr)\\(\"([a-z_]+)\"");

    /**
     * Helpers a tool passes its whole call to; their reads count as the tool's.
     * TODO(JK-JEHR7): add run's McpJobRuns once its schema comes from the JobSpec decoder.
     */
    private static final Map<String, List<String>> DELEGATES = Map.of();

    @Test
    void every_argument_a_tool_reads_is_declared_in_its_schema() throws IOException {
        McpTools tools = McpTools.standard();
        // One class may serve several tools; it may read what any of them declares, and reads
        // only its own operation's arguments (see the graph tools' test below).
        Map<Path, Set<String>> declaredBySource = new TreeMap<>();
        for (String name : tools.names()) {
            McpTool tool = Objects.requireNonNull(tools.tool(name));
            Set<String> declared = properties(tool.spec().inputSchema());
            declaredBySource
                    .computeIfAbsent(source(tool.getClass()), k -> new TreeSet<>())
                    .addAll(declared);
            for (String helper : DELEGATES.getOrDefault(name, List.of())) {
                declaredBySource
                        .computeIfAbsent(source(McpTool.class.getPackageName(), helper), k -> new TreeSet<>())
                        .addAll(declared);
            }
        }
        List<String> undeclared = new ArrayList<>();
        for (Map.Entry<Path, Set<String>> e : declaredBySource.entrySet()) {
            Set<String> read = new TreeSet<>();
            Matcher m = READ.matcher(Files.readString(e.getKey(), StandardCharsets.UTF_8));
            while (m.find()) read.add(m.group(1));
            read.removeAll(e.getValue());
            if (!read.isEmpty()) undeclared.add(e.getKey().getFileName() + " reads " + read);
        }
        assertThat(undeclared).isEmpty();
    }

    @Test
    void diagnostics_declares_the_id_it_reads() {
        McpTools tools = McpTools.standard();
        assertThat(properties(
                        Objects.requireNonNull(tools.tool("diagnostics")).spec().inputSchema()))
                .contains("id");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> properties(Map<String, Object> schema) {
        Object props = schema.get("properties");
        return props instanceof Map<?, ?> m ? ((Map<String, Object>) m).keySet() : Set.of();
    }

    private static Path source(Class<?> type) {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) top = top.getEnclosingClass();
        return source(top.getPackageName(), top.getSimpleName());
    }

    private static Path source(String pkg, String simpleName) {
        return RepoRoot.file(
                McpSchemaReadsTest.class,
                "server/engine/src/main/java/" + pkg.replace('.', '/') + "/" + simpleName + ".java");
    }
}
