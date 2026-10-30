// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One {@code jk graph} operation on the workspace holding {@code dir}. {@code op} is {@code build},
 * {@code query}, {@code callers}, {@code read}, {@code find}, {@code rules}, {@code path} or {@code
 * neighbors}; {@code args} are its positionals. Every read
 * refreshes the graph first unless {@code noRefresh}; {@code rebuild} discards stored fragments.
 * {@code mode} is {@code bfs} or {@code dfs}, breadth-first when unset. {@code scope} is path globs
 * ({@code ;} between them, {@code !} excludes), {@code modules} a module selector as {@code -m} takes
 * it, {@code sourceSet} {@code main} or {@code test}; {@code options} are an operation's named
 * settings as {@code key=value} ({@code view} for {@code read}, the filters for {@code find}).
 */
public record GraphRequest(
        @Nullable String dir,
        @Nullable String op,
        List<String> args,
        @Nullable String mode,
        int depth,
        int budget,
        List<String> contexts,
        boolean undirected,
        int maxHops,
        @Nullable String relation,
        boolean rebuild,
        boolean noRefresh,
        @Nullable String scope,
        @Nullable String modules,
        @Nullable String sourceSet,
        List<String> options) {

    public GraphRequest {
        args = List.copyOf(args);
        contexts = List.copyOf(contexts);
        options = List.copyOf(options);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GRAPH_REQUEST)
                .string("dir", dir)
                .string("op", op)
                .array("args", args)
                .optionalString("mode", mode)
                .number("depth", depth)
                .number("budget", budget)
                .array("contexts", contexts)
                .bool("undirected", undirected)
                .number("maxHops", maxHops)
                .optionalString("relation", relation)
                .bool("rebuild", rebuild)
                .bool("noRefresh", noRefresh)
                .optionalString("scope", scope)
                .optionalString("modules", modules)
                .optionalString("sourceSet", sourceSet)
                .array("options", options)
                .finish();
    }

    public static GraphRequest decode(String json) {
        return new GraphRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "op"),
                Jsonl.strArray(json, "args"),
                Jsonl.str(json, "mode"),
                Jsonl.intValue(json, "depth", 3),
                Jsonl.intValue(json, "budget", 2000),
                Jsonl.strArray(json, "contexts"),
                Jsonl.bool(json, "undirected", false),
                Jsonl.intValue(json, "maxHops", 8),
                Jsonl.str(json, "relation"),
                Jsonl.bool(json, "rebuild", false),
                Jsonl.bool(json, "noRefresh", false),
                Jsonl.str(json, "scope"),
                Jsonl.str(json, "modules"),
                Jsonl.str(json, "sourceSet"),
                Jsonl.strArray(json, "options"));
    }
}
