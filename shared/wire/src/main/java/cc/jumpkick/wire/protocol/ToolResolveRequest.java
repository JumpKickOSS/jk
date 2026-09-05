// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Resolve a Maven-published CLI tool (see {@link EngineProtocol#TOOL_RESOLVE_REQUEST}). {@code
 * coord} is a {@code ToolCoordSpec} string — pinned {@code g:a:v} or floating {@code
 * g:a[@selector]}, pinned engine-side against maven-metadata. {@code with} carries {@code --with}
 * extras (same grammar, may be empty). {@code mainClass} is the {@code --main} override ({@code
 * null} = read the primary jar's manifest engine-side); {@code repoUrl} overrides Maven Central
 * ({@code null} = Central).
 */
public record ToolResolveRequest(
        @Nullable String coord,
        List<String> with,
        @Nullable String bin,
        @Nullable String mainClass,
        @Nullable String repoUrl,
        @Nullable String cache) {

    public ToolResolveRequest {
        with = with == null ? List.of() : List.copyOf(with);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TOOL_RESOLVE_REQUEST)
                .string("coord", coord)
                .array("with", with)
                .string("bin", bin)
                .string("mainClass", mainClass)
                .string("repoUrl", repoUrl)
                .string("cache", cache)
                .finish();
    }

    public static ToolResolveRequest decode(String json) {
        return new ToolResolveRequest(
                Jsonl.str(json, "coord"),
                Jsonl.strArray(json, "with"),
                Jsonl.str(json, "bin"),
                Jsonl.str(json, "mainClass"),
                Jsonl.str(json, "repoUrl"),
                Jsonl.str(json, "cache"));
    }
}
