// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Server → client: calibration result; component ms fields are 0 when not measured (see {@link EngineProtocol#CALIBRATE_ACK}). */
public record CalibrateAckFrame(
        boolean ok,
        double msPerWeight,
        long jvmForkMs,
        long javacMs,
        long diskIoMs,
        long hashCpuMs,
        long junitForkMs,
        long junitRunMs,
        long junitPlatformMs,
        long resolveMs,
        long engineColdStartMs,
        boolean measured,
        boolean junitPlatformUsed,
        boolean resolveUsed,
        String summary) {
    public String encode() {
        return RequestJson.request(EngineProtocol.CALIBRATE_ACK)
                .bool("ok", ok)
                .token("msPerWeight", Double.toString(msPerWeight))
                .number("jvmForkMs", jvmForkMs)
                .number("javacMs", javacMs)
                .number("diskIoMs", diskIoMs)
                .number("hashCpuMs", hashCpuMs)
                .number("junitForkMs", junitForkMs)
                .number("junitRunMs", junitRunMs)
                .number("junitPlatformMs", junitPlatformMs)
                .number("resolveMs", resolveMs)
                .number("engineColdStartMs", engineColdStartMs)
                .bool("measured", measured)
                .bool("junitPlatformUsed", junitPlatformUsed)
                .bool("resolveUsed", resolveUsed)
                .string("summary", summary, "")
                .finish();
    }

    public static CalibrateAckFrame decode(String json) {
        return new CalibrateAckFrame(
                Jsonl.bool(json, "ok", false),
                Jsonl.doubleValue(json, "msPerWeight", 0),
                Jsonl.longValue(json, "jvmForkMs", 0),
                Jsonl.longValue(json, "javacMs", 0),
                Jsonl.longValue(json, "diskIoMs", 0),
                Jsonl.longValue(json, "hashCpuMs", 0),
                Jsonl.longValue(json, "junitForkMs", 0),
                Jsonl.longValue(json, "junitRunMs", 0),
                Jsonl.longValue(json, "junitPlatformMs", 0),
                Jsonl.longValue(json, "resolveMs", 0),
                Jsonl.longValue(json, "engineColdStartMs", 0),
                Jsonl.bool(json, "measured", false),
                Jsonl.bool(json, "junitPlatformUsed", false),
                Jsonl.bool(json, "resolveUsed", false),
                Jsonl.str(json, "summary"));
    }
}
