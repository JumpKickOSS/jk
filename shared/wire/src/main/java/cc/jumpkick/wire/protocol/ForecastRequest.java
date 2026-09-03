// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Pre-flight dirty forecast for a build ({@code jk build}'s fully-cached shortcut); the session's
 * offline/force/rebuild flags ride along so the engine forecasts what the build will do.
 */
public record ForecastRequest(
        @Nullable String dir,
        @Nullable String cache,
        boolean skipTests,
        boolean offline,
        boolean force,
        boolean rebuild) {

    public String encode() {
        return RequestJson.request(EngineProtocol.FORECAST_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .bool("skipTests", skipTests)
                .bool("offline", offline)
                .bool("force", force)
                .bool("rebuild", rebuild)
                .finish();
    }

    public static ForecastRequest decode(String json) {
        return new ForecastRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "rebuild", false));
    }
}
