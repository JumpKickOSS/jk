// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code prompts/*} catalog: one line of playbook per named workflow. Declared once here, so
 * {@code prompts/list} and {@code prompts/get} cannot disagree about which names exist.
 */
public final class McpPrompts {

    private static final Map<String, String> CATALOG = Collections.unmodifiableMap(catalog());

    private McpPrompts() {}

    private static Map<String, String> catalog() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("learn-jumpkick", "skill then follow that playbook (not Maven/Gradle)");
        m.put("fix-failing-build", "run kind=build (the reply is the verdict) then edit then run again");
        m.put("recover-disk", "disk usage then clean or nuke with confirm");
        m.put("setup-ci", "config apply_preset=ci");
        m.put("upgrade-deps", "outdated then read its file then update (preview) then update apply=true");
        m.put("stall-or-cancel", "status then cancel");
        return Collections.unmodifiableMap(m); // list order is the reading order
    }

    /** Every prompt name the surface answers — the tool names inside them are checked by a guard. */
    public static List<String> names() {
        return List.copyOf(CATALOG.keySet());
    }

    /**
     * The one-line playbook for {@code name}, if it exists. One that names a tool off the default
     * {@code tools/list} says how to see that tool's card.
     */
    public static Optional<String> playbook(String name) {
        String text = CATALOG.get(name);
        if (text == null) return Optional.empty();
        List<String> hidden = offList(text);
        if (hidden.isEmpty()) return Optional.of(text);
        return Optional.of(text + " (" + String.join(", ", hidden)
                + " are off the default tool list: tools/list extended=true shows their cards)");
    }

    /** The tools {@code text} names, as words, that are not on {@link McpTools#LOOP}. */
    static List<String> offList(String text) {
        List<String> out = new ArrayList<>();
        for (String tool : McpTools.standard().names()) {
            if (McpTools.LOOP.contains(tool)) continue;
            if (Pattern.compile("\\b" + Pattern.quote(tool) + "\\b")
                    .matcher(text)
                    .find()) out.add(tool);
        }
        return out;
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
