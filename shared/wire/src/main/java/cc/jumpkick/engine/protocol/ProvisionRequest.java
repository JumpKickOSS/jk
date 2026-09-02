// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A Maven or Gradle distribution provisioning request. */
public record ProvisionRequest(
        String dir, String toolsRoot, boolean noDiscover, boolean gradle, String tool, String version) {

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
                .bool("gradle", gradle)
                .optionalTool(tool, version)
                .finish();
    }

    public static ProvisionRequest decode(String json) {
        return new ProvisionRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "toolsRoot"),
                Jsonl.bool(json, "noDiscover", false),
                Jsonl.bool(json, "gradle", false),
                Jsonl.str(json, "tool"),
                Jsonl.str(json, "version"));
    }
}
