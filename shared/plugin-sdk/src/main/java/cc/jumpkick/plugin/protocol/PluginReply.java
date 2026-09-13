// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds {@link PluginProtocol} reply lines (plugin→engine) as JSONL strings. A plugin emits them
 * through its {@link ProtocolWriter} ({@code out.emit(PluginReply.finding(...))}), so no plugin
 * hand-writes the wire JSON. Value types are serialized by shape: {@link String} quoted, numbers
 * and booleans raw, {@link List} as a JSON string array.
 */
public final class PluginReply {

    private PluginReply() {}

    /** A free-text progress label. */
    public static String label(String text) {
        return "{\"t\":\"label\",\"text\":" + Jsonl.quote(text) + "}";
    }

    /** One user-facing output line (command ops). */
    public static String commandOut(String line) {
        return "{\"t\":\"command-out\",\"line\":" + Jsonl.quote(line) + "}";
    }

    /** A structured compiler/format diagnostic; {@code file} may be null, {@code line}/{@code col} 0 when unknown. */
    public static String diagnostic(String sev, @Nullable String file, int line, int col, String msg) {
        StringBuilder b = new StringBuilder("{\"t\":\"diagnostic\",\"sev\":").append(Jsonl.quote(sev));
        if (file != null) b.append(",\"file\":").append(Jsonl.quote(file));
        if (line > 0) b.append(",\"line\":").append(line);
        if (col > 0) b.append(",\"col\":").append(col);
        b.append(",\"msg\":").append(Jsonl.quote(msg)).append('}');
        return b.toString();
    }

    /** Annotation-processing provenance: a generated file and its originating sources. */
    public static String provenance(String generated, List<String> sources) {
        return "{\"t\":\"provenance\",\"gen\":" + Jsonl.quote(generated) + ",\"src\":" + Jsonl.array(sources) + "}";
    }

    /** An audit vulnerability finding; {@code fixedIn} rides only when the feed named a fixed version. */
    public static String finding(
            String module, String version, String id, String severity, String summary, @Nullable String fixedIn) {
        StringBuilder b = new StringBuilder("{\"t\":\"finding\",\"module\":")
                .append(Jsonl.quote(module))
                .append(",\"version\":")
                .append(Jsonl.quote(version))
                .append(",\"id\":")
                .append(Jsonl.quote(id))
                .append(",\"severity\":")
                .append(Jsonl.quote(severity))
                .append(",\"summary\":")
                .append(Jsonl.quote(summary));
        if (fixedIn != null) b.append(",\"fixedIn\":").append(Jsonl.quote(fixedIn));
        return b.append('}').toString();
    }

    /** A formatter per-file outcome. */
    public static String file(String path, String status, @Nullable String msg) {
        StringBuilder b = new StringBuilder("{\"t\":\"file\",\"path\":")
                .append(Jsonl.quote(path))
                .append(",\"status\":")
                .append(Jsonl.quote(status));
        if (msg != null) b.append(",\"msg\":").append(Jsonl.quote(msg));
        return b.append('}').toString();
    }

    /** A compat-import wrote-a-file note. */
    public static String wrote(String path) {
        return "{\"t\":\"wrote\",\"path\":" + Jsonl.quote(path) + "}";
    }

    /** Pull-protocol: the worker can accept one {@code COMPILE}/{@code PLAN} (or {@code DONE}). */
    public static String ready() {
        return "{\"t\":\"ready\"}";
    }

    /**
     * One classpath entry's ABI snapshot, reported by the Kotlin worker's {@code snapshot} op: the
     * entry as the engine named it and the SHA-256 of the snapshot file's bytes.
     */
    public static String classpathSnapshot(String path, String sha256) {
        return JsonFields.object()
                .string("t", "cp-snapshot")
                .string("path", path)
                .string("sha256", sha256)
                .finish();
    }

    /** A terminal typed result payload; {@code fields} serialized by shape. */
    public static String result(Map<String, Object> fields) {
        StringBuilder b = new StringBuilder("{\"t\":\"result\"");
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            b.append(',').append(Jsonl.quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return b.append('}').toString();
    }

    /** A structured error. */
    public static String error(String code, String message) {
        return "{\"t\":\"error\",\"code\":" + Jsonl.quote(code) + ",\"message\":" + Jsonl.quote(message) + "}";
    }

    private static String value(Object v) {
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof List<?> list) {
            List<String> strs = new ArrayList<>();
            for (Object o : list) strs.add(String.valueOf(o));
            return Jsonl.array(strs);
        }
        return Jsonl.quote(String.valueOf(v));
    }
}
