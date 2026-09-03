// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** A single-project test request. */
public record TestRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String jdksDir,
        int workers,
        @Nullable String profile,
        boolean verbose,
        boolean offline,
        boolean force,
        boolean parallelTests,
        @Nullable TestSelection selection,
        /** Who started the build ({@code cli}, {@code web}, {@code ci}, …); the requester's answer, journaled as such. */
        @Nullable String trigger,
        /** Progress-bar mode the requester's environment asked for; null for auto. */
        @Nullable String progressMode) {

    public TestRequest {
        selection = selection == null ? TestSelection.DEFAULT : selection;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TEST_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .number("workers", workers)
                .string("profile", profile)
                .bool("verbose", verbose)
                .bool("offline", offline)
                .bool("force", force)
                .bool("parallelTests", parallelTests)
                .testSelection(selection, false)
                .optionalNonBlankString("trigger", trigger)
                .optionalNonBlankString("progressMode", progressMode)
                .finish();
    }

    public static TestRequest decode(String json) {
        return new TestRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.intValue(json, "workers", 0),
                Jsonl.str(json, "profile"),
                Jsonl.bool(json, "verbose", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "parallelTests", true),
                ProtoJobs.testSelectionOf(json),
                Jsonl.str(json, "trigger"),
                Jsonl.str(json, "progressMode"));
    }
}
