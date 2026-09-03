// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** An artifact publication request, including resolved credentials. */
public record PublishRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String repoUrl,
        @Nullable String region,
        @Nullable String endpoint,
        @Nullable String jar,
        boolean allowSnapshot,
        boolean dryRun,
        @Nullable String keyFile,
        @Nullable String gpgPassphrase,
        boolean sigstore,
        boolean slsa,
        boolean sbom,
        @Nullable String authType,
        @Nullable String user,
        @Nullable String pass,
        @Nullable String token,
        boolean offline,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.PUBLISH_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("repoUrl", repoUrl)
                .string("region", region)
                .string("endpoint", endpoint)
                .string("jar", jar)
                .bool("allowSnapshot", allowSnapshot)
                .bool("dryRun", dryRun)
                .string("keyFile", keyFile)
                .string("gpgPassphrase", gpgPassphrase)
                .bool("sigstore", sigstore)
                .bool("slsa", slsa)
                .bool("sbom", sbom)
                .string("authType", authType)
                .string("user", user)
                .string("pass", pass)
                .string("token", token)
                .bool("offline", offline)
                .bool("verbose", verbose)
                .finish();
    }

    public static PublishRequest decode(String json) {
        return new PublishRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "repoUrl"),
                Jsonl.str(json, "region"),
                Jsonl.str(json, "endpoint"),
                Jsonl.str(json, "jar"),
                Jsonl.bool(json, "allowSnapshot", false),
                Jsonl.bool(json, "dryRun", false),
                Jsonl.str(json, "keyFile"),
                Jsonl.str(json, "gpgPassphrase"),
                Jsonl.bool(json, "sigstore", false),
                Jsonl.bool(json, "slsa", false),
                Jsonl.bool(json, "sbom", false),
                Jsonl.str(json, "authType"),
                Jsonl.str(json, "user"),
                Jsonl.str(json, "pass"),
                Jsonl.str(json, "token"),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "verbose", false));
    }
}
