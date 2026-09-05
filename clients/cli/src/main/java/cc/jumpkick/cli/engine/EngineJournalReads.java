// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.HistoryDeleteRequest;
import cc.jumpkick.wire.protocol.HistoryListRequest;
import cc.jumpkick.wire.protocol.HistoryShowRequest;
import cc.jumpkick.wire.protocol.MetricsRequest;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Thin RPC over the engine's build journal and metrics store: flat JSONL reply lines collected up
 * to the terminal. The terminal rule is asymmetric on purpose — the stream stops <em>before</em>
 * {@code history-done} / {@code metrics-done} and <em>after</em> {@code history-deleted} or an
 * {@code error}, so a caller always sees the line that carries the answer.
 */
public final class EngineJournalReads {

    private EngineJournalReads() {}

    /** Newest-first {@code history-entry} lines (flat JSONL), spawning the engine if none is running. */
    public static List<String> historyList(EnginePaths.Paths paths, int limit) throws IOException {
        return streamHistory(paths, new HistoryListRequest(limit).encode());
    }

    /** One entry's detail: a {@code history-record} header line plus module/step/diag lines. */
    public static List<String> historyShow(EnginePaths.Paths paths, String id) throws IOException {
        return streamHistory(paths, new HistoryShowRequest(id).encode());
    }

    /** Delete one entry; {@code true} if it existed. */
    public static boolean historyDelete(EnginePaths.Paths paths, String id) throws IOException {
        for (String line : streamHistory(paths, new HistoryDeleteRequest(id).encode())) {
            if (EngineProtocol.HISTORY_DELETED.equals(EngineProtocol.typeOf(line))) {
                return Jsonl.bool(line, "deleted", false);
            }
        }
        return false;
    }

    /**
     * Running aggregate rows ({@code metrics-entry} flat JSONL) for {@code dir}'s project tiers
     * plus the global tiers; {@code null} dir asks for every row. Spawns the engine if needed.
     */
    public static List<String> metrics(EnginePaths.Paths paths, @Nullable String dir) throws IOException {
        return streamHistory(paths, new MetricsRequest(dir).encode());
    }

    /** Send a history/metrics request, collect the flat reply lines up to (not including) the terminal. */
    private static List<String> streamHistory(EnginePaths.Paths paths, String request) throws IOException {
        EngineSpawn.ensure(paths, Jk.VERSION);
        List<String> out = new ArrayList<>();
        try (SocketChannel ch = EngineWire.connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            writer.write(request);
            writer.write('\n');
            writer.flush();
            BufferedReader reader = EngineWire.protocolReader(ch);
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.HISTORY_DONE.equals(type) || EngineProtocol.METRICS_DONE.equals(type)) break;
                out.add(line);
                if (EngineProtocol.HISTORY_DELETED.equals(type) || EngineProtocol.ERROR.equals(type)) break;
            }
        }
        return out;
    }
}
