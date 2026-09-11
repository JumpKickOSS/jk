// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.jsonl.Jsonl;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One OSV finding as the engine has judged it — the worker's fields plus the {@code [audit] ignore}
 * entry naming it, if any. Plain structured fields, no theming (see
 * {@link EngineProtocol#AUDIT_FINDING}).
 */
public record AuditFindingEvent(
        String dir,
        String pkg,
        String version,
        String id,
        String severity,
        String summary,
        @Nullable String fixedIn,
        @Nullable String ignoreReason,
        @Nullable String ignoreUntil,
        boolean ignoreExpired) {

    public static AuditFindingEvent of(String dir, AuditReport.Finding f) {
        AuditReport.Ignore ignore = f.ignore();
        return new AuditFindingEvent(
                dir,
                f.module(),
                f.version(),
                f.vulnId(),
                f.severity().name(),
                f.summary(),
                f.fixedIn(),
                ignore == null ? null : ignore.reason(),
                ignore == null || ignore.until() == null ? null : ignore.until().toString(),
                ignore != null && ignore.expired());
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.AUDIT_FINDING)
                .string("dir", dir)
                .string("package", pkg)
                .string("version", version)
                .string("id", id)
                .string("severity", severity)
                .string("summary", summary)
                .string("fixedIn", fixedIn)
                .string("ignoreReason", ignoreReason)
                .string("ignoreUntil", ignoreUntil)
                .bool("ignoreExpired", ignoreExpired)
                .finish();
    }

    public static AuditFindingEvent decode(String json) {
        return new AuditFindingEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "package"),
                Jsonl.requiredStr(json, "version"),
                Jsonl.requiredStr(json, "id"),
                Jsonl.requiredStr(json, "severity"),
                Jsonl.requiredStr(json, "summary"),
                Jsonl.str(json, "fixedIn"),
                Jsonl.str(json, "ignoreReason"),
                Jsonl.str(json, "ignoreUntil"),
                Jsonl.bool(json, "ignoreExpired", false));
    }

    /** The finding this line carries, ignore state included. */
    public AuditReport.Finding toFinding() {
        AuditReport.Ignore ignore = ignoreReason == null
                ? null
                : new AuditReport.Ignore(
                        ignoreReason, ignoreUntil == null ? null : LocalDate.parse(ignoreUntil), ignoreExpired);
        return new AuditReport.Finding(
                pkg, version, id, summary, AuditReport.Severity.parse(severity), fixedIn, ignore);
    }
}
