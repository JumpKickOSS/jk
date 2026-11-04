// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/**
 * The run's downloads summed up: {@code settled} of {@code planned} files of the step that declared
 * its set (both 0 when none did), {@code files} bodies landed and {@code bytes} downloaded so far,
 * {@code inflight} still streaming. {@code done} marks the record that closes a burst.
 */
public record DownloadsLine(long ts, int settled, int planned, int files, long bytes, int inflight, boolean done) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.DOWNLOADS)
                .number("settled", settled)
                .number("planned", planned)
                .number("files", files)
                .number("bytes", bytes)
                .number("inflight", inflight)
                .bool("done", done)
                .finish();
    }

    public static DownloadsLine decode(String json) {
        return new DownloadsLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.intValue(json, "settled", 0),
                Jsonl.intValue(json, "planned", 0),
                Jsonl.intValue(json, "files", 0),
                Jsonl.longValue(json, "bytes", 0),
                Jsonl.intValue(json, "inflight", 0),
                Jsonl.bool(json, "done", false));
    }
}
