// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Notification that a Chrome timeline was written at an absolute path. */
public record TimelineEvent(@Nullable String path) {

    public String encode() {
        return RequestJson.request(EngineProtocol.TIMELINE).string("path", path).finish();
    }

    public static TimelineEvent decode(String json) {
        return new TimelineEvent(Jsonl.str(json, "path"));
    }
}
