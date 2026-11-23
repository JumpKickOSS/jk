// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the job waits before it can run (see {@link EngineProtocol#JOB_QUEUED}). {@code
 * reason} names what it waits for: {@link #MEMORY}, coordinator memory behind {@code ahead} earlier
 * jobs, where {@code waitedMs} is how long it has waited so far ({@code 0} on the first line) and
 * {@code live} names the jobs holding the heap, so the client can say who it is waiting on; or
 * {@link #CACHE}, cache maintenance that holds the cache and must stop or finish first, sent once,
 * after {@link EngineProtocol#JOB_START}, with no jobs ahead.
 */
public record JobQueuedFrame(long jid, int ahead, String reason, long waitedMs, List<Live> live) {

    public static final String MEMORY = "memory";

    /** A cache prune or clean holds the cache; the job runs once it lets go. */
    public static final String CACHE = "cache";

    /** One job holding engine memory while this one waits: its jid, kind, dir and start time. */
    public record Live(long jid, String kind, String dir, long sinceMillis) {

        String encode() {
            return JsonFields.object()
                    .number("jid", jid)
                    .string("kind", kind)
                    .string("dir", dir)
                    .number("since", sinceMillis)
                    .finish();
        }

        static Live decode(String json) {
            String kind = Jsonl.str(json, "kind");
            String dir = Jsonl.str(json, "dir");
            return new Live(
                    Jsonl.longValue(json, "jid", 0),
                    kind == null ? "" : kind,
                    dir == null ? "" : dir,
                    Jsonl.longValue(json, "since", 0));
        }
    }

    public JobQueuedFrame {
        live = live == null ? List.of() : List.copyOf(live);
    }

    public String encode() {
        List<String> rows = new ArrayList<>();
        for (Live l : live) rows.add(l.encode());
        return RequestJson.request(EngineProtocol.JOB_QUEUED)
                .number("jid", jid)
                .number("ahead", ahead)
                .string("reason", reason, MEMORY)
                .number("waitedMs", waitedMs)
                .token("live", "[" + String.join(",", rows) + "]")
                .finish();
    }

    public static JobQueuedFrame decode(String json) {
        String reason = Jsonl.str(json, "reason");
        List<Live> live = new ArrayList<>();
        for (String row : Jsonl.objectArray(json, "live")) live.add(Live.decode(row));
        return new JobQueuedFrame(
                Jsonl.longValue(json, "jid", 0),
                Jsonl.intValue(json, "ahead", 0),
                reason == null ? MEMORY : reason,
                Jsonl.longValue(json, "waitedMs", 0),
                live);
    }
}
