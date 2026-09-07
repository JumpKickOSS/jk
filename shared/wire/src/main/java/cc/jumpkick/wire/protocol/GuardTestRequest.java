// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** {@code jk guard test}: prove every fixture-bearing rule and guard test bites, from {@code dir}'s workspace. */
public record GuardTestRequest(@Nullable String dir) {

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_TEST_REQUEST)
                .string("dir", dir)
                .finish();
    }

    public static GuardTestRequest decode(String json) {
        return new GuardTestRequest(Jsonl.str(json, "dir"));
    }
}
