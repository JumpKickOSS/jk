// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * The new-project form ({@code jk new} on a terminal, {@code jk new -t <ref> --help}). With no
 * {@code template} the answer is jk's own step alone; with one, that step and the template's.
 * {@code dir} is where the project will live, for resolving a template by relative path.
 */
public record NewProjectFormRequest(
        @Nullable String template,
        @Nullable String lang,
        @Nullable String dir) {

    public String encode() {
        return RequestJson.request(EngineProtocol.NEW_PROJECT_FORM_REQUEST)
                .string("template", template)
                .string("lang", lang)
                .string("dir", dir)
                .finish();
    }

    public static NewProjectFormRequest decode(String json) {
        return new NewProjectFormRequest(Jsonl.str(json, "template"), Jsonl.str(json, "lang"), Jsonl.str(json, "dir"));
    }
}
