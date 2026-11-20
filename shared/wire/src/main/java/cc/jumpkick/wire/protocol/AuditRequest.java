// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * An OSV audit request. {@code today} is the caller's local date (ISO {@code YYYY-MM-DD}), the day
 * an {@code [audit] ignore} entry's {@code until} is judged against, so a resident engine started in
 * another zone does not decide it.
 */
public record AuditRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String severity,
        @Nullable String osvBatchUrl,
        @Nullable String osvVulnsUrl,
        boolean offline,
        @Nullable String today) {

    public String encode() {
        return RequestJson.request(EngineProtocol.AUDIT_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("severity", severity)
                .string("osvBatchUrl", osvBatchUrl)
                .string("osvVulnsUrl", osvVulnsUrl)
                .bool("offline", offline)
                .string("today", today)
                .finish();
    }

    public static AuditRequest decode(String json) {
        return new AuditRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "severity"),
                Jsonl.str(json, "osvBatchUrl"),
                Jsonl.str(json, "osvVulnsUrl"),
                Jsonl.bool(json, "offline", false),
                Jsonl.str(json, "today"));
    }
}
