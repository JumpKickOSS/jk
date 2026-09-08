// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: run host hardware calibration; {@code engineColdStartMs} rides only when positive, and the journal trigger is always {@code calibrate} (see {@link EngineProtocol#CALIBRATE_REQUEST}). */
public record CalibrateRequestFrame(boolean force, long engineColdStartMs, boolean allowNetwork) {
    public String encode() {
        return RequestJson.request(EngineProtocol.CALIBRATE_REQUEST)
                .bool("force", force)
                .bool("allowNetwork", allowNetwork)
                .optionalNumber("engineColdStartMs", engineColdStartMs, 0)
                // Synthetic journal classification: a hosted calibrate is a fixture run, never history.
                .string("trigger", "calibrate")
                .finish();
    }

    public static CalibrateRequestFrame decode(String json) {
        return new CalibrateRequestFrame(
                Jsonl.bool(json, "force", false),
                Jsonl.longValue(json, "engineColdStartMs", 0),
                Jsonl.bool(json, "allowNetwork", true));
    }
}
