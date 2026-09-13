// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The engine's {@code jk why} answer ({@link EngineProtocol#WHY_REQUEST}): the lock artifacts
 * matching the query, and every provenance path to each — flat parallel lists per the wire
 * discipline. {@code pathOwners.get(i)} is the index (as a string) into {@code matches} that
 * {@code paths.get(i)} belongs to; each path is {@code module@version} steps joined with
 * {@code >}. {@code pathSelectors.get(i)} carries, for the same path, the selector each step was
 * declared with — one per step, joined with a tab ({@link #STEP_SELECTOR_SEPARATOR}), empty
 * where the lock does not say; a range selector may contain {@code >}, which is why it does not
 * ride inside the path. The client owns matching-free rendering: split and style.
 *
 * <p>{@code error} non-null means the lookup could not run; its message is ready to print.
 */
public record WhyReport(
        @Nullable String error,
        List<String> matchNames,
        List<String> matchVersions,
        List<String> pathOwners,
        List<String> paths,
        List<String> pathSelectors) {

    /** Joins the per-step selectors of one path; no selector grammar contains a tab. */
    public static final String STEP_SELECTOR_SEPARATOR = "\t";

    public static WhyReport error(String message) {
        return new WhyReport(message, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.WHY_ACK)
                .string("error", error)
                .array("matchNames", matchNames)
                .array("matchVersions", matchVersions)
                .array("pathOwners", pathOwners)
                .array("paths", paths)
                .array("pathSelectors", pathSelectors)
                .finish();
    }

    /** The per-step selectors of path {@code index}, {@code ""} where none is known; empty when the wire had none. */
    public List<String> selectorsOf(int index) {
        if (index >= pathSelectors.size()) return List.of();
        return List.of(pathSelectors.get(index).split(STEP_SELECTOR_SEPARATOR, -1));
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
            List<List<String>> declared = new ArrayList<>();
            String idx = Integer.toString(i);
            for (int p = 0; p < paths.size(); p++) {
                if (!idx.equals(pathOwners.get(p))) continue;
                mine.add(paths.get(p));
                declared.add(selectorsOf(p));
            }
            row.put("paths", mine);
            row.put("declared", declared);
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
                Jsonl.strArray(line, "paths"),
                Jsonl.strArray(line, "pathSelectors"));
    }
}
