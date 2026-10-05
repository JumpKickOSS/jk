// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/**
 * A tool install's progress during a {@link EngineProtocol#PROVISION_REQUEST}: {@code phase} is
 * {@code download} (with {@code read} of {@code total} bytes, {@code total} 0 when unknown) or
 * {@code install}; {@code name} is the tool and version ({@code Node.js 24.21.0}).
 */
public record ProvisionProgressEvent(String phase, String name, long read, long total) {

    public static final String DOWNLOAD = "download";
    public static final String INSTALL = "install";

    public String encode() {
        return RequestJson.request(EngineProtocol.PROVISION_PROGRESS)
                .string("phase", phase, DOWNLOAD)
                .string("name", name, "")
                .number("read", read)
                .number("total", total)
                .finish();
    }

    public static ProvisionProgressEvent decode(String json) {
        return new ProvisionProgressEvent(
                Jsonl.requiredStr(json, "phase"),
                Jsonl.requiredStr(json, "name"),
                Jsonl.longValue(json, "read", 0),
                Jsonl.longValue(json, "total", 0));
    }
}
