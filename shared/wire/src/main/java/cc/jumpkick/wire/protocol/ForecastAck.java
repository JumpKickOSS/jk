// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/** The forecast's dirty modules, lock staleness, emptiness and pre-plan errors (see {@link EngineProtocol#FORECAST_ACK}). */
public record ForecastAck(List<String> dirtyDirs, boolean lockStale, boolean empty, List<String> errors) {
    public String encode() {
        return RequestJson.request(EngineProtocol.FORECAST_ACK)
                .array("dirtyDirs", dirtyDirs)
                .bool("lockStale", lockStale)
                .bool("empty", empty)
                .array("errors", errors)
                .finish();
    }

    public static ForecastAck decode(String json) {
        return new ForecastAck(
                Jsonl.strArray(json, "dirtyDirs"),
                Jsonl.bool(json, "lockStale", false),
                Jsonl.bool(json, "empty", false),
                Jsonl.strArray(json, "errors"));
    }
}
