// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Journal a finished {@code jk mvn} run. {@code dir} is the project the client ran Maven in,
 * {@code events} the event file the spy wrote, {@code exit} Maven's exit code, {@code millis} its wall
 * clock and {@code goals} the argv the user gave Maven, space-joined.
 */
public record MvnResultsRequest(
        @Nullable String dir,
        @Nullable String events,
        int exit,
        long millis,
        @Nullable String goals) {

    public String encode() {
        return RequestJson.request(EngineProtocol.MVN_RESULTS_REQUEST)
                .string("dir", dir)
                .string("events", events)
                .number("exit", exit)
                .number("millis", millis)
                .string("goals", goals)
                .finish();
    }

    public static MvnResultsRequest decode(String json) {
        return new MvnResultsRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "events"),
                Jsonl.intValue(json, "exit", 0),
                Jsonl.longValue(json, "millis", 0L),
                Jsonl.str(json, "goals"));
    }
}
