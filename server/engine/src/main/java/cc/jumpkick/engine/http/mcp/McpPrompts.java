// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code prompts/*} catalog: one line of playbook per named workflow. Declared once here, so
 * {@code prompts/list} and {@code prompts/get} cannot disagree about which names exist.
 */
public final class McpPrompts {

    private static final Map<String, String> CATALOG = catalog();

    private McpPrompts() {}

    private static Map<String, String> catalog() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("learn-jumpkick", "jk_manual then follow that playbook (not Maven/Gradle)");
        m.put(
                "fix-failing-build",
                "jk_run kind=build wait=true (the reply is the verdict) then edit then jk_run again");
        m.put("recover-disk", "jk_disk usage then clean or nuke with confirm");
        m.put("setup-ci", "jk_config apply_preset=ci");
        m.put("upgrade-deps", "jk_outdated then read its file then jk_update (preview) then jk_update apply=true");
        m.put("stall-or-cancel", "jk_status then jk_job cancel");
        return Collections.unmodifiableMap(m); // list order is the reading order
    }

    /** Every prompt name the surface answers — the tool names inside them are checked by a guard. */
    public static List<String> names() {
        return List.copyOf(CATALOG.keySet());
    }

    /** The one-line playbook for {@code name}, if it exists. */
    public static Optional<String> playbook(String name) {
        return Optional.ofNullable(CATALOG.get(name));
    }

    public static Map<String, Object> list() {
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Map.Entry<String, String> e : CATALOG.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("description", e.getValue());
            ps.add(m);
        }
        return Map.of("prompts", ps);
    }

    public static Map<String, Object> get(Map<String, Object> params) {
        Object raw = params.get("name");
        String name = raw == null ? null : String.valueOf(raw);
        if (name == null || name.isBlank()) throw new McpError(-32602, "prompts/get requires name");
        String description = CATALOG.get(name);
        if (description == null) throw new McpError(-32602, "unknown prompt: " + name);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", description);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", description);
        result.put("messages", List.of(message));
        return result;
    }
}
