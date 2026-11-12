// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * The {@code jk explain} burst is complete: the widest ready set, the module count, and the
 * estimate's critical path as module dirs, first first (see {@link EngineProtocol#EXPLAIN_DONE}).
 */
public record ExplainDoneEvent(int maxReadyWidth, int moduleCount, List<String> criticalPath) {

    public ExplainDoneEvent {
        criticalPath = criticalPath == null ? List.of() : List.copyOf(criticalPath);
    }

    public ExplainDoneEvent(int maxReadyWidth, int moduleCount) {
        this(maxReadyWidth, moduleCount, List.of());
    }

    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.EXPLAIN_DONE)
                .number("maxReadyWidth", maxReadyWidth)
                .number("moduleCount", moduleCount);
        if (!criticalPath.isEmpty()) json.array("criticalPath", criticalPath);
        return json.finish();
    }

    public static ExplainDoneEvent decode(String json) {
        return new ExplainDoneEvent(
                Jsonl.intValue(json, "maxReadyWidth", 0),
                Jsonl.intValue(json, "moduleCount", 0),
                Jsonl.strArray(json, "criticalPath"));
    }
}
