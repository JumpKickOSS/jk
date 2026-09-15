// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/**
 * One declared pin {@code jk update} moved (or, in preview, would move): {@code handle} in the
 * {@code table} of the manifest under {@code dir} goes {@code from} → {@code to} (see {@link
 * EngineProtocol#UPDATE_REWRITE}). {@code module} is the {@code group:artifact} behind the handle.
 */
public record UpdateRewriteEvent(String dir, String table, String handle, String module, String from, String to) {

    public String encode() {
        return RequestJson.request(EngineProtocol.UPDATE_REWRITE)
                .string("dir", dir)
                .string("table", table)
                .string("handle", handle)
                .string("module", module)
                .string("from", from)
                .string("to", to)
                .finish();
    }

    public static UpdateRewriteEvent decode(String json) {
        return new UpdateRewriteEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "table"),
                Jsonl.requiredStr(json, "handle"),
                Jsonl.requiredStr(json, "module"),
                Jsonl.requiredStr(json, "from"),
                Jsonl.requiredStr(json, "to"));
    }
}
