// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One {@code jk graph} operation on the workspace holding {@code dir}. {@code op} is {@code build},
 * {@code query}, {@code explain}, {@code path}, {@code node}, {@code neighbors}, {@code community},
 * {@code god-nodes}, {@code stats} or {@code report}; {@code args} are its positionals. Every read
 * refreshes the graph first unless {@code noRefresh}; {@code rebuild} discards stored fragments.
 */
public record GraphRequest(
        String dir,
        String op,
        List<String> args,
        String mode,
        int depth,
        int budget,
        List<String> contexts,
        boolean all,
        boolean undirected,
        int maxHops,
        int topN,
        @Nullable String relation,
        boolean rebuild,
        boolean noRefresh) {

    public GraphRequest {
        args = List.copyOf(args);
        contexts = List.copyOf(contexts);
    }

    /** {@code op} with every option at its default. */
    public static GraphRequest of(String dir, String op, List<String> args) {
        return new GraphRequest(dir, op, args, "bfs", 3, 2000, List.of(), false, false, 8, 10, null, false, false);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GRAPH_REQUEST)
                .string("dir", dir)
                .string("op", op)
                .array("args", args)
                .string("mode", mode)
                .number("depth", depth)
                .number("budget", budget)
                .array("contexts", contexts)
                .bool("all", all)
                .bool("undirected", undirected)
                .number("maxHops", maxHops)
                .number("topN", topN)
                .optionalString("relation", relation)
                .bool("rebuild", rebuild)
                .bool("noRefresh", noRefresh)
                .finish();
    }

    private static String mode(String json) {
        String mode = Jsonl.str(json, "mode");
        return mode == null ? "bfs" : mode;
    }

    public static GraphRequest decode(String json) {
        return new GraphRequest(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "op"),
                Jsonl.strArray(json, "args"),
                mode(json),
                Jsonl.intValue(json, "depth", 3),
                Jsonl.intValue(json, "budget", 2000),
                Jsonl.strArray(json, "contexts"),
                Jsonl.bool(json, "all", false),
                Jsonl.bool(json, "undirected", false),
                Jsonl.intValue(json, "maxHops", 8),
                Jsonl.intValue(json, "topN", 10),
                Jsonl.str(json, "relation"),
                Jsonl.bool(json, "rebuild", false),
                Jsonl.bool(json, "noRefresh", false));
    }
}
