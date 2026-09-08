// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** The commit-message verdict: the rendered failures and verdict line; {@code failures} drives the hook's exit code. */
public record GuardCommitMsgAck(@Nullable String error, String text, int failures) {

    public static GuardCommitMsgAck error(String message) {
        return new GuardCommitMsgAck(message, "", 1);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_COMMIT_MSG_ACK)
                .string("error", error)
                .string("text", text)
                .number("failures", failures)
                .finish();
    }

    public static GuardCommitMsgAck decode(String line) {
        String text = Jsonl.str(line, "text");
        return new GuardCommitMsgAck(
                Jsonl.str(line, "error"), text == null ? "" : text, Jsonl.intValue(line, "failures", 0));
    }
}
