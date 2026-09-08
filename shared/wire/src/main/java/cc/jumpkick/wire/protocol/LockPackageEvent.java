// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * One resolved package, or a coalesced sample: {@code totalSeen >= 0} is the cumulative count at emit
 * time and rides as {@code total}; {@code -1} means one package, no total (see {@link EngineProtocol#LOCK_PACKAGE}).
 */
public record LockPackageEvent(
        @Nullable String dir, String name, @Nullable String version, int totalSeen) {
    public String encode() {
        return RequestJson.request(EngineProtocol.LOCK_PACKAGE)
                .string("dir", dir)
                .string("name", name)
                .string("version", version)
                .optionalNumber("total", totalSeen, -1)
                .finish();
    }

    public static LockPackageEvent decode(String json) {
        return new LockPackageEvent(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "name"),
                Jsonl.str(json, "version"),
                Jsonl.intValue(json, "total", -1));
    }
}
