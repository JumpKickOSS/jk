// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.Map;
import java.util.TreeMap;

/**
 * One symbol, file, heading or config key. {@code fileType} is {@code code}, {@code document},
 * {@code rationale} or {@code concept}; {@code sourceLocation} is {@code L<line>}, empty when
 * unknown. {@code extra} carries every other field verbatim.
 */
public record GraphNode(
        String id, String label, String fileType, String sourceFile, String sourceLocation, Map<String, Object> extra) {

    public GraphNode {
        extra = Map.copyOf(new TreeMap<>(extra));
    }

    public GraphNode(String id, String label, String fileType, String sourceFile, String sourceLocation) {
        this(id, label, fileType, sourceFile, sourceLocation, Map.of());
    }

    /** The label folded for matching: diacritics stripped, lower-cased. */
    public String normLabel() {
        return GraphText.fold(label);
    }

    /** {@code L<line>} for a 1-based line, empty for a line below 1. */
    public static String location(int line) {
        return line < 1 ? "" : "L" + line;
    }
}
