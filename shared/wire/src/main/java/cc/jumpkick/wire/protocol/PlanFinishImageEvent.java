// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestSummary;
import org.jspecify.annotations.Nullable;

/**
 * An image plan's terminal: the test counts (the image plan runs the full plan) and the Image
 * chip's ingredients — exactly one of tarball, daemon exe, or neither (registry push) is non-null
 * (see {@link EngineProtocol#BUILDPLAN_FINISH}).
 */
public record PlanFinishImageEvent(
        String dir,
        boolean success,
        long total,
        long succeeded,
        long failed,
        long skipped,
        @Nullable String ref,
        @Nullable String tarball,
        @Nullable String name,
        @Nullable String version,
        @Nullable String daemonExe) {
    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "image")
                .string("dir", dir)
                .bool("success", success);
        if (total >= 0) json.token(TestSummary.WIRE_KEY, TestSummary.countsJson(total, succeeded, failed, skipped));
        return json.string("imageRef", ref)
                .string("imageTarball", tarball)
                .string("imageName", name)
                .string("imageVersion", version)
                .string("imageDaemonExe", daemonExe)
                .finish();
    }

    public static PlanFinishImageEvent decode(String json) {
        String tests = Jsonl.nested(json, TestSummary.WIRE_KEY);
        return new PlanFinishImageEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                tests == null ? -1 : Jsonl.longValue(tests, "total", -1),
                tests == null ? -1 : Jsonl.longValue(tests, "succeeded", -1),
                tests == null ? -1 : Jsonl.longValue(tests, "failed", -1),
                tests == null ? -1 : Jsonl.longValue(tests, "skipped", -1),
                Jsonl.str(json, "imageRef"),
                Jsonl.str(json, "imageTarball"),
                Jsonl.str(json, "imageName"),
                Jsonl.str(json, "imageVersion"),
                Jsonl.str(json, "imageDaemonExe"));
    }
}
