// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * Cache/store inventory ({@link EngineProtocol#CACHE_INVENTORY_REQUEST}). {@code stats} rows are
 * {@code name|files|bytes}. Repo search rows are {@code group|artifact|v1,v2}.
 */
public record CacheInventoryAck(
        String error,
        String query,
        List<String> stats,
        long totalFiles,
        long totalBytes,
        List<String> entries,
        List<String> lines,
        int evicted,
        int missed,
        long files,
        long bytes) {

    public static CacheInventoryAck error(String message) {
        return new CacheInventoryAck(message, "", List.of(), 0, 0, List.of(), List.of(), 0, 0, 0, 0);
    }

    public static CacheInventoryAck usage(String query, List<String> stats, long totalFiles, long totalBytes) {
        return new CacheInventoryAck(
                null, query, List.copyOf(stats), totalFiles, totalBytes, List.of(), List.of(), 0, 0, 0, 0);
    }

    public static CacheInventoryAck repoSearch(List<String> entries) {
        return new CacheInventoryAck(null, "repo-search", List.of(), 0, 0, List.copyOf(entries), List.of(), 0, 0, 0, 0);
    }

    public static CacheInventoryAck repoRefresh(List<String> lines, int evicted, int missed) {
        return new CacheInventoryAck(
                null, "repo-refresh", List.of(), 0, 0, List.of(), List.copyOf(lines), evicted, missed, 0, 0);
    }

    public static CacheInventoryAck wipe(long files, long bytes) {
        return new CacheInventoryAck(null, "wipe-store", List.of(), 0, 0, List.of(), List.of(), 0, 0, files, bytes);
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.CACHE_INVENTORY_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"query\":" + Jsonl.quote(query)
                + ",\"stats\":" + EngineProtocol.quoteArray(stats)
                + ",\"totalFiles\":" + totalFiles
                + ",\"totalBytes\":" + totalBytes
                + ",\"entries\":" + EngineProtocol.quoteArray(entries)
                + ",\"lines\":" + EngineProtocol.quoteArray(lines)
                + ",\"evicted\":" + evicted
                + ",\"missed\":" + missed
                + ",\"files\":" + files
                + ",\"bytes\":" + bytes
                + "}";
    }

    public static CacheInventoryAck decode(String line) {
        return new CacheInventoryAck(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "query")),
                Jsonl.strArray(line, "stats"),
                Jsonl.longValue(line, "totalFiles", 0),
                Jsonl.longValue(line, "totalBytes", 0),
                Jsonl.strArray(line, "entries"),
                Jsonl.strArray(line, "lines"),
                Jsonl.intValue(line, "evicted", 0),
                Jsonl.intValue(line, "missed", 0),
                Jsonl.longValue(line, "files", 0),
                Jsonl.longValue(line, "bytes", 0));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
