// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for {@link EngineProtocol#TEMPLATE_LINT_REQUEST}. {@code checked} counts the templates
 * linted; {@code failures} holds one line per failing template or path, naming the file and the
 * property. {@code error} is set when the request itself could not run.
 */
public record TemplateLintAck(@Nullable String error, int checked, List<String> failures) {

    public TemplateLintAck {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static TemplateLintAck error(String message) {
        return new TemplateLintAck(message, 0, List.of());
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TEMPLATE_LINT_ACK)
                .string("error", error)
                .number("checked", checked)
                .array("failures", failures)
                .finish();
    }

    public static TemplateLintAck decode(String line) {
        return new TemplateLintAck(
                Jsonl.str(line, "error"), Jsonl.intValue(line, "checked", 0), Jsonl.strArray(line, "failures"));
    }
}
