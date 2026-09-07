// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * The fixture report: one line per rule, load errors first; {@code failures} counts rules not proven
 * plus load errors, so the client's exit code is the engine's verdict.
 */
public record GuardTestAck(@Nullable String error, String text, int failures) {

    public static GuardTestAck error(String message) {
        return new GuardTestAck(message, "", 1);
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.GUARD_TEST_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"text\":" + Jsonl.quote(text)
                + ",\"failures\":" + failures
                + "}";
    }

    public static GuardTestAck decode(String line) {
        String text = Jsonl.str(line, "text");
        return new GuardTestAck(
                Jsonl.str(line, "error"), text == null ? "" : text, Jsonl.intValue(line, "failures", 0));
    }
}
