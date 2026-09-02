// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A native-image tracing-agent training request. */
public record TrainRequest(
        String dir,
        String cache,
        String jdksDir,
        String graalHome,
        String profile,
        boolean force,
        boolean skipTests,
        boolean offline,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.TRAIN_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .string("graalHome", graalHome)
                .string("profile", profile)
                .bool("force", force)
                .bool("skipTests", skipTests)
                .bool("offline", offline)
                .bool("verbose", verbose)
                .finish();
    }

    public static TrainRequest decode(String json) {
        return new TrainRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.str(json, "graalHome"),
                Jsonl.str(json, "profile"),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "verbose", false));
    }
}
