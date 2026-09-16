// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * A Maven or Gradle distribution provisioning request; {@code acceptUnverified} installs an
 * archive no checksum vouches for and records its digest.
 */
public record ProvisionRequest(
        @Nullable String dir,
        @Nullable String toolsRoot,
        boolean noDiscover,
        boolean acceptUnverified,
        boolean gradle,
        @Nullable String tool,
        @Nullable String version) {

    public ProvisionRequest {
        if (tool == null || tool.isBlank()) {
            tool = null;
            version = null;
        } else if (version == null) {
            version = "";
        }
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.PROVISION_REQUEST)
                .string("dir", dir)
                .string("toolsRoot", toolsRoot)
                .bool("noDiscover", noDiscover)
                .bool("acceptUnverified", acceptUnverified)
                .bool("gradle", gradle)
                .optionalTool(tool, version)
                .finish();
    }

    public static ProvisionRequest decode(String json) {
        return new ProvisionRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "toolsRoot"),
                Jsonl.bool(json, "noDiscover", false),
                Jsonl.bool(json, "acceptUnverified", false),
                Jsonl.bool(json, "gradle", false),
                Jsonl.str(json, "tool"),
                Jsonl.str(json, "version"));
    }
}
