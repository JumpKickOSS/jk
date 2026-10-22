// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A reference in a document that names nothing: a relative link to a file that is not there, or a
 * qualified class name in one of the workspace's packages that no source declares. {@code kind} is
 * {@code file} or {@code class}; {@code target} is the link resolved from the workspace root, or the
 * class name.
 */
public record StaleRef(String file, int line, String kind, String target) {

    public static final String FILE = "file";
    public static final String CLASS = "class";

    /** At most this many are kept: the report lists them, it is not a link checker. */
    public static final int LIMIT = 50;

    static List<Map<String, Object>> tree(List<StaleRef> refs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StaleRef r : refs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", r.file());
            row.put("line", r.line());
            row.put("kind", r.kind());
            row.put("ref", r.target());
            out.add(row);
        }
        return out;
    }

    static List<StaleRef> ofTree(@Nullable Object tree) {
        List<StaleRef> out = new ArrayList<>();
        if (!(tree instanceof List<?> rows)) return out;
        for (Object o : rows) {
            if (o instanceof Map<?, ?> m
                    && m.get("file") instanceof String file
                    && m.get("line") instanceof Number line
                    && m.get("kind") instanceof String kind
                    && m.get("ref") instanceof String target) {
                out.add(new StaleRef(file, line.intValue(), kind, target));
            }
        }
        return out;
    }
}
