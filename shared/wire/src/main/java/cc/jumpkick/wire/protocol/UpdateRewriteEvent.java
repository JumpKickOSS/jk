// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * One declared pin {@code jk update} moved (or, in preview, would move): {@code handle} in the
 * {@code table} of the manifest under {@code dir} goes {@code from} → {@code to} (see {@link
 * EngineProtocol#UPDATE_REWRITE}). {@code module} is the {@code group:artifact} behind the handle.
 * {@code held} marks a move not taken, a person's to accept: {@code from} is where the pin stands
 * after the run. {@code adds} names the packages, {@code coordinate version}, the move's relock adds
 * to the lock, another reason a person accepts it; empty when it adds none.
 */
public record UpdateRewriteEvent(
        String dir,
        String table,
        String handle,
        String module,
        String from,
        String to,
        boolean held,
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
                .bool("held", held)
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
                Jsonl.bool(json, "held", false),
                Jsonl.strArray(json, "adds"));
    }
}
