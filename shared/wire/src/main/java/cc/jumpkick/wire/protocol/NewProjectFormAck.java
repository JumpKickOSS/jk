// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for {@link EngineProtocol#NEW_PROJECT_FORM_REQUEST}. {@code form} is the form's own JSON
 * ({@code cc.jumpkick.form.Form#toJson}); {@code template} is the id the request's ref resolved to,
 * empty without one; {@code templates} maps every catalogue id ({@code lang/framework/name}) to its
 * description, in catalogue order, for the template search.
 */
public record NewProjectFormAck(@Nullable String error, String form, String template, Map<String, String> templates) {

    public NewProjectFormAck {
        templates = templates == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    public static NewProjectFormAck error(String message) {
        return new NewProjectFormAck(message, "", "", Map.of());
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.NEW_PROJECT_FORM_ACK)
                .string("error", error)
                .string("form", form)
                .string("template", template)
                .map("templates", templates)
                .finish();
    }

    public static NewProjectFormAck decode(String line) {
        return new NewProjectFormAck(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "form")),
                orEmpty(Jsonl.str(line, "template")),
                Jsonl.strMap(line, "templates"));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
