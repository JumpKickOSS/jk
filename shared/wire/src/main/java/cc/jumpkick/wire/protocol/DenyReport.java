// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Engine-side {@code [deny]} check result ({@link EngineProtocol#DENY_CHECK_REQUEST}); policy is
 * never client-parsed. Violations are parallel {@code modules}/{@code versions}/{@code reasons}
 * lists; non-null {@code error} is printable.
 */
public record DenyReport(
        @Nullable String error, int checked, List<String> modules, List<String> versions, List<String> reasons) {

    public static DenyReport error(String message) {
        return new DenyReport(message, 0, List.of(), List.of(), List.of());
    }

    public int violationCount() {
        return modules.size();
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.DENY_CHECK_ACK)
                .string("error", error)
                .number("checked", checked)
                .array("modules", modules)
                .array("versions", versions)
                .array("reasons", reasons)
                .finish();
    }

    public static DenyReport decode(String line) {
        return new DenyReport(
                Jsonl.str(line, "error"),
                Jsonl.intValue(line, "checked", 0),
                Jsonl.strArray(line, "modules"),
                Jsonl.strArray(line, "versions"),
                Jsonl.strArray(line, "reasons"));
    }
}
