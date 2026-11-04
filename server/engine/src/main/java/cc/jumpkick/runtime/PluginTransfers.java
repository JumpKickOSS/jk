// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.build.Transfers;
import cc.jumpkick.task.TransferBoard;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A plugin worker's own downloads on the run's board: each {@code transfer} reply (the SDK's
 * {@code Transfers}) opens, moves or ends a row there, exactly as an engine download does. Built
 * on the run's thread, so the board is the run's however the reader thread is scheduled; closing
 * it ends any row the worker left open, as a worker that died mid-body would.
 */
public final class PluginTransfers implements AutoCloseable {

    private final TransferBoard board;
    private final Map<String, TransferBoard.Transfer> open = new ConcurrentHashMap<>();

    public PluginTransfers(TransferBoard board) {
        this.board = Objects.requireNonNull(board, "board");
    }

    /** Relay {@code client}'s {@code transfer} replies onto the current run's board until closed. */
    public static PluginTransfers relay(PluginClient client) {
        PluginTransfers transfers =
                new PluginTransfers(SessionContext.current().io().transfers());
        client.on(Transfers.REPLY, transfers::accept);
        return transfers;
    }

    /** One {@code transfer} reply. A malformed one is dropped: a progress row is never worth a failed step. */
    public void accept(String line) {
        String id = Jsonl.str(line, "id");
        String event = Jsonl.str(line, "e");
        if (id == null || event == null) return;
        switch (event) {
            case "start" -> {
                String label = Jsonl.str(line, "label");
                if (label == null) return;
                TransferBoard.Transfer t =
                        Jsonl.bool(line, "artifact", false) ? board.openArtifact(label) : board.open(label);
                TransferBoard.Transfer previous = open.put(id, t);
                if (previous != null) previous.close();
                t.started(Jsonl.longValue(line, "total", -1L));
            }
            case "read" -> {
                TransferBoard.Transfer t = open.get(id);
                if (t != null) t.advanced(Jsonl.longValue(line, "read", 0L));
            }
            case "done" -> {
                TransferBoard.Transfer t = open.remove(id);
                if (t != null) {
                    t.finished();
                    t.close();
                }
            }
            case "close" -> {
                TransferBoard.Transfer t = open.remove(id);
                if (t != null) t.close();
            }
            default -> {
                // an event this engine does not know: a newer SDK's, ignored
            }
        }
    }

    /** Rows the worker opened and has not ended. */
    int open() {
        return open.size();
    }

    @Override
    public void close() {
        for (TransferBoard.Transfer t : open.values()) t.close();
        open.clear();
    }
}
