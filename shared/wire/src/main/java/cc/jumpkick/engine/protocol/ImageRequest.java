// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** An OCI image build request. {@code tarball} preserves its null, empty, or explicit-path states. */
public record ImageRequest(
        String dir,
        String cache,
        String jdksDir,
        String mainClass,
        String registry,
        String tag,
        String tarball,
        String dockerExecutable,
        boolean skipTests,
        boolean offline,
        boolean force,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.IMAGE_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .string("mainClass", mainClass)
                .string("registry", registry)
                .string("tag", tag)
                .string("tarball", tarball)
                .string("dockerExecutable", dockerExecutable)
                .bool("skipTests", skipTests)
                .bool("offline", offline)
                .bool("force", force)
                .bool("verbose", verbose)
                .finish();
    }

    public static ImageRequest decode(String json) {
        return new ImageRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.str(json, "mainClass"),
                Jsonl.str(json, "registry"),
                Jsonl.str(json, "tag"),
                Jsonl.str(json, "tarball"),
                Jsonl.str(json, "dockerExecutable"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false));
    }
}
