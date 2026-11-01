// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.docs.JkSkill;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** {@code skill} — the JumpKick skill core, or one topic. Same bytes as {@code jk skill}. */
public final class SkillTool implements McpTool {

    static final String DESCRIPTION =
            "How to use jk: the loop and the verdict format. topic= one page (dependencies, tests, lockfile, …).";

    /** Another registered tool's description by name: agents ask for a tool's name as a topic. */
    private volatile Function<String, @Nullable String> tools = name -> null;

    /** Wired by the registry once every tool is known. */
    public void tools(Function<String, @Nullable String> descriptions) {
        this.tools = descriptions;
    }

    @Override
    public Spec spec() {
        return new Spec(
                "skill", DESCRIPTION, McpSchemas.object(Map.of("topic", McpSchemas.string())), McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String topic = in.str("topic");
        if (topic == null || topic.isBlank()) {
            return in.text(JkSkill.core());
        }
        String text = JkSkill.topic(topic);
        if (text != null) return in.text(text);
        // Not an error: an agent that guessed a topic gets its next move in the same answer.
        String name = topic.strip();
        String topics = "Topics: " + String.join(", ", JkSkill.TOPICS) + "; no topic gives the core.\n";
        String tool = tools.apply(name);
        if (tool != null) {
            return in.text("`" + name + "` is an MCP tool, not a topic: call it directly. " + tool + "\n" + topics);
        }
        return in.text("No skill topic `" + name + "`. " + topics);
    }
}
