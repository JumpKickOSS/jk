// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.ProtocolWriter;
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
        return JsonFields.object().string("t", "label").string("text", text).finish();
    }

    /** One user-facing output line (command ops). */
    public static String commandOut(String line) {
        return JsonFields.object()
                .string("t", "command-out")
                .string("line", line)
                .finish();
    }

    /** A structured compiler/format diagnostic; {@code file} may be null, {@code line}/{@code col} 0 when unknown. */
    public static String diagnostic(String sev, @Nullable String file, int line, int col, String msg) {
        return diagnostic(sev, file, line, col, msg, "");
    }

    /**
     * A compiler diagnostic with the compiler's own key for it ({@code
     * compiler.err.cant.resolve.location}); the {@code key} field is written only when the compiler
     * gave one.
     */
    public static String diagnostic(
            String sev, @Nullable String file, int line, int col, String msg, @Nullable String key) {
        return JsonFields.object()
                .string("t", "diagnostic")
                .string("sev", sev)
                .optionalString("file", file)
                .optionalNumber("line", line, 0)
                .optionalNumber("col", col, 0)
                .optionalNonEmptyString("key", key)
                .string("msg", msg)
                .finish();
    }

    /** Annotation-processing provenance: a generated file and its originating sources. */
    public static String provenance(String generated, List<String> sources) {
        return JsonFields.object()
                .string("t", "provenance")
                .string("gen", generated)
                .array("src", sources)
                .finish();
    }

    /**
     * An audit vulnerability finding; {@code fixedIn} rides only when the feed named a fixed version,
     * {@code aliases} only when the advisory has other ids.
     */
    public static String finding(
            String module,
            String version,
            String id,
            String severity,
            String summary,
            @Nullable String fixedIn,
            List<String> aliases) {
        return JsonFields.object()
                .string("t", "finding")
                .string("module", module)
                .string("version", version)
                .string("id", id)
                .string("severity", severity)
                .string("summary", summary)
                .optionalString("fixedIn", fixedIn)
                .optionalArray("aliases", aliases)
                .finish();
    }

    /** A formatter per-file outcome. */
    public static String file(String path, String status, @Nullable String msg) {
        return JsonFields.object()
                .string("t", "file")
                .string("path", path)
                .string("status", status)
                .optionalString("msg", msg)
                .finish();
    }

    /** A compat-import wrote-a-file note. */
    public static String wrote(String path) {
        return JsonFields.object().string("t", "wrote").string("path", path).finish();
    }

    /** Pull-protocol: the worker can accept one {@code COMPILE}/{@code PLAN} (or {@code DONE}). */
    public static String ready() {
        return JsonFields.object().string("t", "ready").finish();
    }

    /** Serve mode: one run ended with {@code code}; {@code retire} when the worker exits next. */
    public static String exited(int code, boolean retire) {
        return JsonFields.object()
                .string("t", PluginProtocol.EXITED)
                .number("code", code)
                .bool("retire", retire)
                .finish();
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

    /** The sources one pass of an incremental compile recompiled, each as the worker was handed it. */
    public static String compiled(List<String> files) {
        return JsonFields.object()
                .string("t", PluginProtocol.COMPILED)
                .array("files", files)
                .finish();
    }

    /** A terminal typed result payload; {@code fields} serialized by shape. */
    public static String result(Map<String, Object> fields) {
        JsonFields json = JsonFields.object().string("t", "result");
        for (Map.Entry<String, Object> e : fields.entrySet()) json.token(e.getKey(), value(e.getValue()));
        return json.finish();
    }

    /** A structured error. */
    public static String error(String code, String message) {
        return JsonFields.object()
                .string("t", "error")
                .string("code", code)
                .string("message", message)
                .finish();
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
