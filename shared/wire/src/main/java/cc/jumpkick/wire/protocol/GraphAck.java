// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The answer to a {@link GraphRequest}: the operation's text (empty for {@code build}) and the
 * graph it was answered from. {@code files} files were walked and {@code extracted} of them read
 * again; {@code dir} is where the graph was written; {@code note} is what the user should see about
 * the answer, such as compiled facts left out of it; {@code uncompiled} names the modules and test
 * directories with sources and no compiled classes, whose calls the answer is missing. {@code error}
 * is set, and nothing else is meaningful, when the request failed.
 */
public record GraphAck(
        @Nullable String error,
        String text,
        int nodes,
        int edges,
        int files,
        int extracted,
        int classes,
        long millis,
        String dir,
        @Nullable String note,
        List<String> uncompiled) {

    public GraphAck {
        uncompiled = uncompiled == null ? List.of() : List.copyOf(uncompiled);
    }

    public static GraphAck error(String message) {
        return new GraphAck(message, "", 0, 0, 0, 0, 0, 0, "", null, List.of());
    }

    public boolean ok() {
        return error == null;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GRAPH_ACK)
                .optionalString("error", error)
                .string("text", text)
                .number("nodes", nodes)
                .number("edges", edges)
                .number("files", files)
                .number("extracted", extracted)
                .number("classes", classes)
                .number("millis", millis)
                .string("dir", dir)
                .optionalString("note", note)
                .optionalArray("uncompiled", uncompiled)
                .finish();
    }

    public static GraphAck decode(String json) {
        String text = Jsonl.str(json, "text");
        String dir = Jsonl.str(json, "dir");
        return new GraphAck(
                Jsonl.str(json, "error"),
                text == null ? "" : text,
                Jsonl.intValue(json, "nodes", 0),
                Jsonl.intValue(json, "edges", 0),
                Jsonl.intValue(json, "files", 0),
                Jsonl.intValue(json, "extracted", 0),
                Jsonl.intValue(json, "classes", 0),
                Jsonl.longValue(json, "millis", 0),
                dir == null ? "" : dir,
                Jsonl.str(json, "note"),
                Jsonl.strArray(json, "uncompiled"));
    }
}
