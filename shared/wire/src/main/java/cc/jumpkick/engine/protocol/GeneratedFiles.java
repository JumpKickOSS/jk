// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * Generator file payloads for {@link EngineProtocol#GENERATE_REQUEST}: parallel absolute
 * {@code paths}/{@code contents}, {@code notes} as {@code severity|message}. Non-null {@code error}
 * is printable.
 */
public record GeneratedFiles(String error, List<String> paths, List<String> contents, List<String> notes) {

    public static GeneratedFiles error(String message) {
        return new GeneratedFiles(message, List.of(), List.of(), List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.GENERATE_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"paths\":" + EngineProtocol.quoteArray(paths)
                + ",\"contents\":" + EngineProtocol.quoteArray(contents)
                + ",\"notes\":" + EngineProtocol.quoteArray(notes)
                + "}";
    }

    public static GeneratedFiles decode(String line) {
        return new GeneratedFiles(
                Jsonl.str(line, "error"),
                Jsonl.strArray(line, "paths"),
                Jsonl.strArray(line, "contents"),
                Jsonl.strArray(line, "notes"));
    }
}
