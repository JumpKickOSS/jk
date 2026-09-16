// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for a provision request: the tool's launcher path ({@code null} on failure), the
 * source/version for the client's one-line note, what vouched for a downloaded archive, the
 * failure text when {@code exit != 0} (see {@link EngineProtocol#PROVISION_RESULT}).
 */
public record ProvisionResultEvent(
        @Nullable String bin,
        @Nullable String version,
        @Nullable String source,
        @Nullable String verification,
        @Nullable String error,
        int exit) {
    public String encode() {
        return RequestJson.request(EngineProtocol.PROVISION_RESULT)
                .string("bin", bin)
                .string("version", version)
                .string("source", source)
                .string("verification", verification)
                .string("error", error)
                .number("exit", exit)
                .finish();
    }

    public static ProvisionResultEvent decode(String json) {
        return new ProvisionResultEvent(
                Jsonl.str(json, "bin"),
                Jsonl.str(json, "version"),
                Jsonl.str(json, "source"),
                Jsonl.str(json, "verification"),
                Jsonl.str(json, "error"),
                Jsonl.intValue(json, "exit", 0));
    }
}
