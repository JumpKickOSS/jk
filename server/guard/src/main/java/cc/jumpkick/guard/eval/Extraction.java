// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What one extractor read: rows keyed by the element {@code parity} compares, each carrying the named
 * columns a {@code generated} template may print.
 *
 * @param label how the source is named in a message, e.g. {@code workspace-modules(jk.toml)}
 * @param file the workspace-relative file the rows came from, when the extractor read one
 */
record Extraction(String label, @Nullable String file, List<Row> rows) {

    Extraction {
        rows = List.copyOf(rows);
    }

    /** One element and its columns; {@code columns} always contains the key under its own name. */
    record Row(String key, Map<String, String> columns) {
        Row {
            columns = Map.copyOf(columns);
        }

        String column(String name) {
            String v = columns.get(name);
            return v == null ? "" : v;
        }
    }

    Set<String> keys() {
        Set<String> out = new TreeSet<>();
        for (Row r : rows) out.add(r.key());
        return out;
    }

    /** The keys in the source's own order, each once: what a template prints. */
    List<String> keyList() {
        List<String> out = new ArrayList<>();
        for (Row r : rows) if (!out.contains(r.key())) out.add(r.key());
        return out;
    }
}
