// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Scaffold a project from a template ({@code jk new}); no template is the default one.
 * {@code targetDir} and {@code cwd} ride only when set. {@code cwd} is the caller's working
 * directory, which a relative template path resolves against; without it, {@code parentDir}.
 * {@code agents} ({@code detect}, {@code all} or {@code none}) and {@code agentHosts} are the
 * caller's agents setting and the agents on its machine, which the create wires the project for; no
 * {@code agents} wires nothing.
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
        @Nullable String targetDir,
        @Nullable String cwd,
        @Nullable String agents,
        List<String> agentHosts) {

    public NewProjectRequest {
        templateParams =
                templateParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(templateParams));
        agentHosts = agentHosts == null ? List.of() : List.copyOf(agentHosts);
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
                .optionalNonBlankString("cwd", cwd)
                .optionalNonBlankString("agents", agents)
                .optionalArray("agentHosts", agentHosts)
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
                Jsonl.str(json, "targetDir"),
                Jsonl.str(json, "cwd"),
                Jsonl.str(json, "agents"),
                Jsonl.strArray(json, "agentHosts"));
    }
}
