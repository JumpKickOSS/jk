// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.docs.JkSkill;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code skill} — the JumpKick skill core, or one topic. Same bytes as {@code jk skill}. */
public final class SkillTool implements McpTool {

    static final String DESCRIPTION =
            "How to use jk: the loop and the verdict format. topic= one page (dependencies, tests, lockfile, …).";

    @Override
    public Spec spec() {
        return new Spec(
                "skill", DESCRIPTION, McpSchemas.object(Map.of("topic", McpSchemas.string())), McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String topic = in.str("topic");
        if (topic == null || topic.isBlank()) {
            return in.ok(McpEnvelope.of("skill", Map.of("resource", "jk://skill")), JkSkill.core());
        }
        String text = JkSkill.topic(topic);
        if (text == null) throw new McpError(-32602, "unknown skill topic: " + topic);
        return in.ok(McpEnvelope.of("skill", Map.of("topic", topic, "resource", "jk://skill/" + topic)), text);
    }
}
