// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * A lock/update module's terminal with its written-lockfile counts, how many of its packages the
 * write added, removed or moved to another version ({@code changed}), and what the run's downloads
 * were checked against: {@code unverified} artifacts pinned without a published checksum under
 * {@code allow-unverified}, and the plaintext {@code http://} repositories asked (see {@link
 * EngineProtocol#BUILDPLAN_FINISH}).
 */
public record PlanFinishLockEvent(
        String dir,
        boolean success,
        long packages,
        long changed,
        long sources,
        long plugins,
        long unverified,
        List<String> insecureRepos) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "lock")
                .string("dir", dir)
                .bool("success", success)
                .number("lockPackages", packages)
                .number("lockChanged", changed)
                .number("lockSources", sources)
                .number("lockPlugins", plugins)
                .number("lockUnverified", unverified)
                .array("lockInsecure", insecureRepos)
                .finish();
    }

    public static PlanFinishLockEvent decode(String json) {
        return new PlanFinishLockEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "lockPackages", 0),
                Jsonl.longValue(json, "lockChanged", 0),
                Jsonl.longValue(json, "lockSources", 0),
                Jsonl.longValue(json, "lockPlugins", 0),
                Jsonl.longValue(json, "lockUnverified", 0),
                Jsonl.strArray(json, "lockInsecure"));
    }
}
