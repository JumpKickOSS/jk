// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_deps} — preview or apply a surgical dependency edit in jk.toml. */
public final class DepsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_deps",
                "Preview/apply surgical dependency edits (g:n:v). apply=false by default.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("add | remove"),
                        "coords",
                        McpSchemas.strings(),
                        "scope",
                        McpSchemas.string("main|test|runtime|provided|processor"),
                        "apply",
                        McpSchemas.bool(),
                        "dir",
                        McpSchemas.string())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        boolean apply = in.flag("apply");
        Map<String, Object> data =
                McpManifest.deps(in.requiredDir(), in.action("add"), in.strings("coords"), in.str("scope"), apply);
        return in.ok(
                McpEnvelope.of("deps", data, false, null, ManifestEdits.relockHint(data)),
                apply ? "deps applied" : "deps preview");
    }
}
