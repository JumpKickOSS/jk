// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** How {@code jk decide} reads and prints the decisions the engine lists. */
final class DecideView {

    private DecideView() {}

    /** The decisions in a {@code decisions-ack}'s JSON array. */
    static List<Map<?, ?>> parse(String json) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (MiniJson.parse(json) instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(m);
        }
        return out;
    }

    static List<Map<?, ?>> open(List<Map<?, ?>> all) {
        return all.stream().filter(d -> "open".equals(MiniJson.str(d, "state"))).toList();
    }

    /** The decision {@code id} names, whole or by a unique prefix of four or more characters. */
    static @Nullable Map<?, ?> find(List<Map<?, ?>> all, String id) {
        Map<?, ?> found = null;
        for (Map<?, ?> d : all) {
            String each = String.valueOf(MiniJson.str(d, "id"));
            if (each.equals(id)) return d;
            if (id.length() >= 4 && each.startsWith(id)) {
                if (found != null) return null;
                found = d;
            }
        }
        return found;
    }

    /** One row per open decision, or the line that says there are none. */
    static List<String> table(List<Map<?, ?>> open) {
        if (open.isEmpty()) return List.of("No decision is waiting for you.");
        List<String> rows = new ArrayList<>();
        rows.add(open.size() + (open.size() == 1 ? " decision waits" : " decisions wait") + " for you:");
        for (Map<?, ?> d : open) rows.add("  " + MiniJson.str(d, "id") + "  " + oneLine(d));
        return rows;
    }

    /** {@code question  (kind, label)} on one line. */
    static String oneLine(Map<?, ?> d) {
        StringBuilder out = new StringBuilder(String.valueOf(MiniJson.str(d, "question")));
        List<String> tags = new ArrayList<>();
        tags.add(String.valueOf(MiniJson.str(d, "kind")));
        String label = MiniJson.str(d, "label");
        if (label != null) tags.add(label);
        return out.append("  (").append(String.join(", ", tags)).append(')').toString();
    }

    /** The decision in full before its form is asked: the question, who asks, and the detail. */
    static List<String> card(Map<?, ?> d) {
        List<String> lines = new ArrayList<>();
        lines.add(String.valueOf(MiniJson.str(d, "question")));
        String label = MiniJson.str(d, "label");
        String project = MiniJson.str(d, "project");
        if (label != null || project != null) {
            String who = label != null ? label : "setup".equals(MiniJson.str(d, "kind")) ? "JumpKick" : "an agent";
            lines.add("  asked by " + who + (project == null ? "" : " in " + project));
        }
        for (Object line : MiniJson.list(d, "detail")) lines.add("  " + line);
        return lines;
    }
}
