// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Server → client: job admitted; {@code jid} is the cancel handle, {@code buildNumber}, {@code detailsPath} and {@code etaMs} ride only when known (see {@link EngineProtocol#JOB_START}). */
public record JobStartFrame(
        long jid,
        @Nullable String kind,
        @Nullable String dir,
        long buildNumber,
        @Nullable String detailsPath,
        long etaMs) {
    public String encode() {
        return RequestJson.request(EngineProtocol.JOB_START)
                .number("jid", jid)
                .string("kind", kind, "")
                .string("dir", dir, "")
                .optionalNumber("buildNumber", buildNumber, 0)
                .optionalNonBlankString("detailsPath", detailsPath)
                .optionalNumber("etaMs", etaMs, -1)
                .finish();
    }

    public static JobStartFrame decode(String json) {
        return new JobStartFrame(
                Jsonl.longValue(json, "jid", 0),
                Jsonl.str(json, "kind"),
                Jsonl.str(json, "dir"),
                Jsonl.longValue(json, "buildNumber", 0),
                Jsonl.str(json, "detailsPath"),
                Jsonl.longValue(json, "etaMs", -1));
    }
}
