// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Generator file payloads for {@link EngineProtocol#GENERATE_REQUEST}: parallel absolute
 * {@code paths}/{@code contents}, {@code notes} as {@code severity|message}. Non-null {@code error}
 * is printable.
 */
public record GeneratedFiles(@Nullable String error, List<String> paths, List<String> contents, List<String> notes) {

    public static GeneratedFiles error(String message) {
        return new GeneratedFiles(message, List.of(), List.of(), List.of());
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GENERATE_ACK)
                .string("error", error)
                .array("paths", paths)
                .array("contents", contents)
                .array("notes", notes)
                .finish();
    }

    public static GeneratedFiles decode(String line) {
        return new GeneratedFiles(
                Jsonl.str(line, "error"),
                Jsonl.strArray(line, "paths"),
                Jsonl.strArray(line, "contents"),
                Jsonl.strArray(line, "notes"));
    }
}
