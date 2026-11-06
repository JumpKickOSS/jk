// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Scaffold a project from a template (CLI {@code jk new}, HTTP, MCP); no template is the default
 * one. {@code targetDir} rides only when set.
 */
public record NewProjectRequest(
        @Nullable String name,
        @Nullable String parentDir,
        @Nullable String group,
        @Nullable String lang,
        @Nullable String layout,
        @Nullable String template,
        boolean standalone,
        Map<String, String> templateParams,
        boolean relaxParent,
        @Nullable String targetDir) {

    public NewProjectRequest {
        templateParams =
                templateParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(templateParams));
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.NEW_PROJECT_REQUEST)
                .string("name", name)
                .string("parentDir", parentDir)
                .string("group", group)
                .string("lang", lang)
                .string("layout", layout)
                .string("template", template)
                .bool("standalone", standalone)
                .map("templateParams", templateParams)
                .bool("relaxParent", relaxParent)
                .optionalNonBlankString("targetDir", targetDir)
                .finish();
    }

    public static NewProjectRequest decode(String json) {
        return new NewProjectRequest(
                Jsonl.str(json, "name"),
                Jsonl.str(json, "parentDir"),
                Jsonl.str(json, "group"),
                Jsonl.str(json, "lang"),
                Jsonl.str(json, "layout"),
                Jsonl.str(json, "template"),
                Jsonl.bool(json, "standalone", true),
                Jsonl.strMap(json, "templateParams"),
                Jsonl.bool(json, "relaxParent", false),
                Jsonl.str(json, "targetDir"));
    }
}
