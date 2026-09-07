// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** {@code jk guard commit-msg}: judge one commit message against {@code dir}'s workspace commit rules. */
public record GuardCommitMsgRequest(
        @Nullable String dir, @Nullable String message) {

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_COMMIT_MSG_REQUEST)
                .string("dir", dir)
                .string("message", message)
                .finish();
    }

    public static GuardCommitMsgRequest decode(String json) {
        return new GuardCommitMsgRequest(Jsonl.str(json, "dir"), Jsonl.str(json, "message"));
    }
}
