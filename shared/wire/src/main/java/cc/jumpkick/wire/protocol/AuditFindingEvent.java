// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One OSV finding — plain structured fields, no theming (see {@link EngineProtocol#AUDIT_FINDING}). */
public record AuditFindingEvent(
        String dir, String module, String version, String vulnId, String severity, String summary) {
    public String encode() {
        return RequestJson.request(EngineProtocol.AUDIT_FINDING)
                .string("dir", dir)
                .string("module", module)
                .string("version", version)
                .string("vulnId", vulnId)
                .string("severity", severity)
                .string("summary", summary)
                .finish();
    }

    public static AuditFindingEvent decode(String json) {
        return new AuditFindingEvent(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "module"),
                Jsonl.str(json, "version"),
                Jsonl.str(json, "vulnId"),
                Jsonl.str(json, "severity"),
                Jsonl.str(json, "summary"));
    }
}
