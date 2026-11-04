// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.task.TransferBoard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A run's downloads in flight (see {@link EngineProtocol#DOWNLOADS}). */
public record DownloadsEvent(TransferBoard.Snapshot snapshot, boolean done) {

    /** A frame mid-burst. */
    public DownloadsEvent(TransferBoard.Snapshot snapshot) {
        this(snapshot, false);
    }

    public String encode() {
        List<String> rows = new ArrayList<>(snapshot.rows().size());
        for (TransferBoard.Row r : snapshot.rows()) {
            rows.add(JsonFields.object()
                    .string("label", r.label())
                    .number("read", r.read())
                    .number("total", r.total())
                    .finish());
        }
        return RequestJson.request(EngineProtocol.DOWNLOADS)
                .number("settled", snapshot.settled())
                .number("planned", snapshot.planned())
                .number("files", snapshot.files())
                .number("bytes", snapshot.bytes())
                .number("inflight", snapshot.inflight())
                .token("rows", "[" + String.join(",", rows) + "]")
                .optionalTrue("done", done)
                .finish();
    }

    public static DownloadsEvent decode(String json) {
        List<TransferBoard.Row> rows = new ArrayList<>();
        for (String row : Jsonl.objectArray(json, "rows")) {
            rows.add(new TransferBoard.Row(
                    Objects.requireNonNullElse(Jsonl.str(row, "label"), ""),
                    Jsonl.longValue(row, "read", 0L),
                    Jsonl.longValue(row, "total", -1L)));
        }
        return new DownloadsEvent(
                new TransferBoard.Snapshot(
                        Jsonl.intValue(json, "settled", 0),
                        Jsonl.intValue(json, "planned", 0),
                        Jsonl.intValue(json, "files", 0),
                        Jsonl.longValue(json, "bytes", 0L),
                        Jsonl.intValue(json, "inflight", 0),
                        rows),
                Jsonl.bool(json, "done", false));
    }
}
