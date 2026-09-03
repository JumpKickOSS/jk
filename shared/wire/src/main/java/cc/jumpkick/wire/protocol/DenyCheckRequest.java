// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Check the project's lock against its deny policy. */
public record DenyCheckRequest(@Nullable String dir) {

    public String encode() {
        return RequestJson.request(EngineProtocol.DENY_CHECK_REQUEST)
                .string("dir", dir)
                .finish();
    }

    public static DenyCheckRequest decode(String json) {
        return new DenyCheckRequest(Jsonl.str(json, "dir"));
    }
}
