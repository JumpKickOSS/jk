// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's {@code jk why} answer ({@link EngineProtocol#WHY_REQUEST}): the lock artifacts
 * matching the query, and every provenance path to each — flat parallel lists per the wire
 * discipline. {@code pathOwners.get(i)} is the index (as a string) into {@code matches} that
 * {@code paths.get(i)} belongs to; each path is {@code module@version} steps joined with
 * {@code >}. The client owns matching-free rendering: split and style.
 *
 * <p>{@code error} non-null means the lookup could not run; its message is ready to print.
 */
public record WhyReport(
        String error,
        List<String> matchNames,
        List<String> matchVersions,
        List<String> pathOwners,
        List<String> paths) {

    public static WhyReport error(String message) {
        return new WhyReport(message, List.of(), List.of(), List.of(), List.of());
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.WHY_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"matchNames\":" + EngineProtocol.quoteArray(matchNames)
                + ",\"matchVersions\":" + EngineProtocol.quoteArray(matchVersions)
                + ",\"pathOwners\":" + EngineProtocol.quoteArray(pathOwners)
                + ",\"paths\":" + EngineProtocol.quoteArray(paths)
                + "}";
    }

    /**
     * Structured form for map-shaped surfaces (MCP {@code structuredContent}): one row per match
     * with its own provenance paths — the same facts {@link #encode} flattens for the wire.
     */
    public Map<String, Object> toStructured() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (error != null) {
            m.put("error", error);
            return m;
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < matchNames.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", matchNames.get(i));
            row.put("version", i < matchVersions.size() ? matchVersions.get(i) : "");
            List<String> mine = new ArrayList<>();
            String idx = Integer.toString(i);
            for (int p = 0; p < paths.size(); p++) {
                if (idx.equals(pathOwners.get(p))) mine.add(paths.get(p));
            }
            row.put("paths", mine);
            matches.add(row);
        }
        m.put("matches", matches);
        return m;
    }

    public static WhyReport decode(String line) {
        return new WhyReport(
                Jsonl.str(line, "error"),
                Jsonl.strArray(line, "matchNames"),
                Jsonl.strArray(line, "matchVersions"),
                Jsonl.strArray(line, "pathOwners"),
                Jsonl.strArray(line, "paths"));
    }
}
