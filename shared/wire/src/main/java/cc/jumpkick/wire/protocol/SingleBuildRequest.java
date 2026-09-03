// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** A single-project build request. */
public record SingleBuildRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String jdksDir,
        int workers,
        @Nullable String profile,
        boolean skipTests,
        boolean verbose,
        boolean offline,
        boolean force,
        @Nullable TestSelection selection) {

    public SingleBuildRequest {
        selection = selection == null ? TestSelection.DEFAULT : selection;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.SINGLE_BUILD_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .number("workers", workers)
                .string("profile", profile)
                .bool("skipTests", skipTests)
                .bool("verbose", verbose)
                .bool("offline", offline)
                .bool("force", force)
                .testSelection(selection, true)
                .finish();
    }

    public static SingleBuildRequest decode(String json) {
        return new SingleBuildRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.intValue(json, "workers", 0),
                Jsonl.str(json, "profile"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "verbose", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                ProtoJobs.testSelectionOf(json));
    }
}
