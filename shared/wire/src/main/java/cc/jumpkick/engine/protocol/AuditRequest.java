// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** An OSV audit request. */
public record AuditRequest(
        String dir, String cache, String severity, String osvBatchUrl, String osvVulnsUrl, boolean offline) {

    public String encode() {
        return RequestJson.request(EngineProtocol.AUDIT_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("severity", severity)
                .string("osvBatchUrl", osvBatchUrl)
                .string("osvVulnsUrl", osvVulnsUrl)
                .bool("offline", offline)
                .finish();
    }

    public static AuditRequest decode(String json) {
        return new AuditRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "severity"),
                Jsonl.str(json, "osvBatchUrl"),
                Jsonl.str(json, "osvVulnsUrl"),
                Jsonl.bool(json, "offline", false));
    }
}
