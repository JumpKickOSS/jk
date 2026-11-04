// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** The docs verdict: the rendered red rules and a verdict line; {@code failures} is the exit code's source. */
public record GuardDocsAck(@Nullable String error, String text, int failures) {

    public static GuardDocsAck error(String message) {
        return new GuardDocsAck(message, "", 1);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_DOCS_ACK)
                .string("error", error)
                .string("text", text)
                .number("failures", failures)
                .finish();
    }

    public static GuardDocsAck decode(String line) {
        String text = Jsonl.str(line, "text");
        return new GuardDocsAck(
                Jsonl.str(line, "error"), text == null ? "" : text, Jsonl.intValue(line, "failures", 0));
    }
}
