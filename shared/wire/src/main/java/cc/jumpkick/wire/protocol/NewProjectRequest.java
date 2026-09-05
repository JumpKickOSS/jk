// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Scaffold a project (CLI {@code jk new}, HTTP, MCP). {@code targetDir} rides only when set. */
public record NewProjectRequest(
        @Nullable String name,
        @Nullable String parentDir,
        @Nullable String group,
        @Nullable String lang,
        @Nullable String layout,
        @Nullable String template,
        boolean executable,
        @Nullable String jdk,
        int javaRelease,
        boolean assembly,
        boolean nativeImage,
        boolean plugin,
        @Nullable String kotlinModule,
        List<String> deps,
        boolean sample,
        boolean standalone,
        @Nullable Map<String, String> templateParams,
        boolean relaxParent,
        @Nullable String targetDir) {

    public NewProjectRequest {
        deps = deps == null ? List.of() : List.copyOf(deps);
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
                .bool("executable", executable)
                .string("jdk", jdk)
                .number("javaRelease", javaRelease)
                .bool("assembly", assembly)
                .bool("nativeImage", nativeImage)
                .bool("plugin", plugin)
                .string("kotlinModule", kotlinModule)
                .array("deps", deps)
                .bool("sample", sample)
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
                Jsonl.bool(json, "executable", false),
                Jsonl.str(json, "jdk"),
                Jsonl.intValue(json, "javaRelease", 0),
                Jsonl.bool(json, "assembly", false),
                Jsonl.bool(json, "nativeImage", false),
                Jsonl.bool(json, "plugin", false),
                Jsonl.str(json, "kotlinModule"),
                Jsonl.strArray(json, "deps"),
                Jsonl.bool(json, "sample", true),
                Jsonl.bool(json, "standalone", true),
                Jsonl.strMap(json, "templateParams"),
                Jsonl.bool(json, "relaxParent", false),
                Jsonl.str(json, "targetDir"));
    }
}
