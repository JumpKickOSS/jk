// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Layered library-catalog snapshot ({@link EngineProtocol#CATALOG_READ_REQUEST}). Non-null {@code
 * error} is printable and {@code entries} is empty. Wire rows are {@code |}-joined 5-tuples ({@code
 * name|group|artifact|layer|v1,v2}); catalog names and Maven GAs never contain {@code |}.
 */
public record CatalogReadAck(
        @Nullable String error, List<String> warnings, List<String> layerNames, List<Entry> entries) {

    /** One short-name resolution plus optional locally-cached versions (newest first). */
    public record Entry(String name, String group, String artifact, String layer, List<String> cached) {
        public String moduleKey() {
            return group + ":" + artifact;
        }
    }

    public static CatalogReadAck error(String message) {
        return new CatalogReadAck(message, List.of(), List.of(), List.of());
    }

    public static CatalogReadAck of(List<String> warnings, List<String> layerNames, List<Entry> entries) {
        return new CatalogReadAck(null, List.copyOf(warnings), List.copyOf(layerNames), List.copyOf(entries));
    }

    public String encode() {
        List<String> encoded = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            encoded.add(String.join("|", e.name(), e.group(), e.artifact(), e.layer(), String.join(",", e.cached())));
        }
        return "{\"type\":\"" + EngineProtocol.CATALOG_READ_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"warnings\":" + EngineProtocol.quoteArray(warnings)
                + ",\"layerNames\":" + EngineProtocol.quoteArray(layerNames)
                + ",\"entries\":" + EngineProtocol.quoteArray(encoded)
                + "}";
    }

    public static CatalogReadAck decode(String line) {
        String error = Jsonl.str(line, "error");
        List<Entry> entries = new ArrayList<>();
        for (String enc : Jsonl.strArray(line, "entries")) {
            String[] f = enc.split("\\|", -1);
            List<String> cached = List.of();
            String raw = at(f, 4);
            if (!raw.isEmpty()) {
                cached = List.of(raw.split(",", -1));
            }
            entries.add(new Entry(at(f, 0), at(f, 1), at(f, 2), at(f, 3), cached));
        }
        return new CatalogReadAck(
                error, Jsonl.strArray(line, "warnings"), Jsonl.strArray(line, "layerNames"), List.copyOf(entries));
    }

    private static String at(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }
}
