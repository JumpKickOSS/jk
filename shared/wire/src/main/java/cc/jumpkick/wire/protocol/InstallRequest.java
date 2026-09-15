// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * A project build and cache-install request. {@code jdksDir} is the JDK root the plan resolves
 * under ({@code --jdks-dir}), read into the session as on every build-shaped request.
 */
public record InstallRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String jdksDir,
        @Nullable String m2Dir,
        @Nullable String graalHome,
        boolean skipTests,
        boolean offline,
        boolean force,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.INSTALL_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .string("m2Dir", m2Dir)
                .string("graalHome", graalHome)
                .bool("skipTests", skipTests)
                .bool("offline", offline)
                .bool("force", force)
                .bool("verbose", verbose)
                .finish();
    }

    public static InstallRequest decode(String json) {
        return new InstallRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.str(json, "m2Dir"),
                Jsonl.str(json, "graalHome"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false));
    }
}
