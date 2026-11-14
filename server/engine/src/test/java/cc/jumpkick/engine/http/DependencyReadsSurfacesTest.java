// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.tools.OutdatedTool;
import cc.jumpkick.engine.http.mcp.tools.UpdateTool;
import cc.jumpkick.engine.http.mcp.tools.WhyTool;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dependency reads on REST answer the object the MCP tool returns for the same input:
 * {@code GET /api/project/outdated} as {@code outdated}, {@code GET /api/project/update} as {@code
 * update}'s preview, and {@code GET /api/project/why} as the report {@code why} renders.
 */
@Tag("integration")
@SuppressWarnings({"NullAway", "unchecked"})
class DependencyReadsSurfacesTest extends HttpEngineServerHarness {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path app;

    @AfterEach
    void release() {
        LockfileReader.clearCache();
    }

    @Test
    void outdated_update_preview_and_why_answer_the_same_on_rest_and_mcp() throws Exception {
        project();
        String dir = URLEncoder.encode(app.toString(), StandardCharsets.UTF_8);

        HttpResponse<String> outdated = get("/api/project/outdated?dir=" + dir);
        assertThat(outdated.statusCode()).as(outdated.body()).isEqualTo(200);
        Map<String, Object> mcpOutdated = data(new OutdatedTool().call(call(Map.of("dir", app.toString()))));
        assertThat(MiniJson.parse(outdated.body())).isEqualTo(mcpOutdated);
        assertThat(outdated.body()).contains("com.acme:other");

        HttpResponse<String> update = get("/api/project/update?dir=" + dir + "&deps=other");
        assertThat(update.statusCode()).as(update.body()).isEqualTo(200);
        Map<String, Object> mcpUpdate =
                data(new UpdateTool().call(call(Map.of("dir", app.toString(), "deps", List.of("other")))));
        assertThat(MiniJson.parse(update.body())).isEqualTo(mcpUpdate);
        assertThat(update.body()).contains("\"to\":\"1.1.0\"").doesNotContain("2.18.2");

        HttpResponse<String> why = get("/api/project/why?dir=" + dir + "&coord=com.acme:other");
        assertThat(why.statusCode()).as(why.body()).isEqualTo(200);
        assertThat(why.body()).contains("com.acme:other");
        String mcpWhy = text(new WhyTool().call(call(Map.of("dir", app.toString(), "coord", "com.acme:other"))));
        assertThat(mcpWhy).contains("com.acme:other").contains("1.0.0");

        assertThat(get("/api/project/update?dir=" + dir + "&dep=other").statusCode())
                .as("a misspelt parameter is refused")
                .isEqualTo(400);
    }

    private static McpCall call(Map<String, Object> args) {
        return new McpCall(null, args, null);
    }

    /** The tool's structured envelope without the envelope's own keys: the operation's object. */
    private static Map<String, Object> data(Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) result.get("structuredContent"));
        for (String key : List.of("schema", "type", "truncated", "next", "hint")) m.remove(key);
        return (Map<String, Object>) MiniJson.parse(MiniJson.write(m));
    }

    private static String text(Map<String, Object> result) {
        Map<String, Object> row = (Map<String, Object>)
                Objects.requireNonNull((List<?>) result.get("content")).getFirst();
        return String.valueOf(row.get("text"));
    }

    private void project() throws Exception {
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        for (String[] a : List.of(
                new String[] {"jackson", "2.18.0", "2.18.2", "3.0.0"}, new String[] {"other", "1.0.0", "1.1.0"})) {
            String[] versions = Arrays.copyOfRange(a, 1, a.length);
            for (String v : versions) {
                upstream.leaf("com.acme", a[0], v);
            }
            upstream.metadata("com.acme", a[0], versions);
        }
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "%s"

                [dependencies]
                jackson = "com.acme:jackson:2.18.0"
                other = "com.acme:other:1.0.0"
                """.formatted(http.baseUrl()));
        LockFlow.Result first = LockFlow.run(app, app.resolve("cache"), List.of(), false, null);
        assertThat(first.status()).as("the first lock: %s", first.error()).isZero();
    }
}
