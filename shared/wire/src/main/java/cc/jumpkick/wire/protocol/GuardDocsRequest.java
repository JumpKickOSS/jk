// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** {@code jk guard docs}: the Markdown rules of {@code dir}'s workspace, run with no build. */
public record GuardDocsRequest(@Nullable String dir) {

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_DOCS_REQUEST)
                .string("dir", dir)
                .finish();
    }

    public static GuardDocsRequest decode(String json) {
        return new GuardDocsRequest(Jsonl.str(json, "dir"));
    }
}
