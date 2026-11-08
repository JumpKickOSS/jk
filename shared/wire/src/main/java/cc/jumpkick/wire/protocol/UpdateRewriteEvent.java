// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * One declared pin {@code jk update} moved (or, in preview, would move): {@code handle} in the
 * {@code table} of the manifest under {@code dir} goes {@code from} → {@code to} (see {@link
 * EngineProtocol#UPDATE_REWRITE}). {@code module} is the {@code group:artifact} behind the handle.
 * {@code available} marks a release past the pin's major the run did not take ({@code --major}
 * takes it): {@code from} is where the pin stands after the run. In a preview {@code adds} names the
 * packages, {@code coordinate version}, the move's relock would add to the lock; empty otherwise.
 */
public record UpdateRewriteEvent(
        String dir,
        String table,
        String handle,
        String module,
        String from,
        String to,
        boolean available,
        List<String> adds) {

    public UpdateRewriteEvent {
        adds = adds == null ? List.of() : List.copyOf(adds);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.UPDATE_REWRITE)
                .string("dir", dir)
                .string("table", table)
                .string("handle", handle)
                .string("module", module)
                .string("from", from)
                .string("to", to)
                .bool("available", available)
                .array("adds", adds)
                .finish();
    }

    public static UpdateRewriteEvent decode(String json) {
        return new UpdateRewriteEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "table"),
                Jsonl.requiredStr(json, "handle"),
                Jsonl.requiredStr(json, "module"),
                Jsonl.requiredStr(json, "from"),
                Jsonl.requiredStr(json, "to"),
                Jsonl.bool(json, "available", false),
                Jsonl.strArray(json, "adds"));
    }
}
