// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A lockfile synchronization request. */
public record SyncRequest(
        String dir,
        String cache,
        String jdksDir,
        String repoUrl,
        boolean sources,
        boolean offline,
        boolean force,
        boolean refresh,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.SYNC_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .string("repoUrl", repoUrl)
                .bool("sources", sources)
                .bool("offline", offline)
                .bool("force", force)
                .bool("refresh", refresh)
                .bool("verbose", verbose)
                .finish();
    }

    public static SyncRequest decode(String json) {
        return new SyncRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.str(json, "repoUrl"),
                Jsonl.bool(json, "sources", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "refresh", false),
                Jsonl.bool(json, "verbose", false));
    }
}
