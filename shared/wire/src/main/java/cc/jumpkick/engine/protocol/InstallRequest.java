// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A project build and cache-install request. */
public record InstallRequest(
        String dir,
        String cache,
        String m2Dir,
        String graalHome,
        boolean skipTests,
        boolean offline,
        boolean force,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.INSTALL_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
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
                Jsonl.str(json, "m2Dir"),
                Jsonl.str(json, "graalHome"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false));
    }
}
