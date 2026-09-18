// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import org.jspecify.annotations.Nullable;

/** Engine job binding — jid (cancel), buildNumber (run dir), ETA and details path; each rides only when known. */
public record JobLine(
        long ts,
        long jid,
        long buildNumber,
        long etaMs,
        @Nullable String detailsPath) {
    public String encode() {
        return JsonlEnvelope.open(ts, "job")
                .optionalNumber("jid", jid, 0)
                .optionalNumber("buildNumber", buildNumber, 0)
                .optionalNumber("etaMs", etaMs, -1)
                .optionalNonBlankString("detailsPath", detailsPath)
                .finish();
    }

    public static JobLine decode(String json) {
        return new JobLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.longValue(json, "jid", 0),
                Jsonl.longValue(json, "buildNumber", 0),
                Jsonl.longValue(json, "etaMs", -1),
                Jsonl.str(json, "detailsPath"));
    }
}
