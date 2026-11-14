// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.tools.ExplainTool;
import cc.jumpkick.engine.verbs.Forecasts;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.SettledHostCalibration;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * One forecast on every surface: the explain request {@code jk explain -m a} sends, {@code GET
 * /api/explain} and MCP {@code explain} with the same selection decode to the same request and
 * answer the same object: the same modules, dirty set and ETAs.
 */
@Tag("integration")
@SuppressWarnings("NullAway")
@ExtendWith(SettledHostCalibration.class)
class ForecastSurfacesTest extends HttpEngineServerHarness {

    @TempDir
    Path ws;

    @Test
    @SuppressWarnings("unchecked")
    void cli_rest_and_mcp_forecast_the_same_build() throws Exception {
        workspace();
        String dir = ws.toString();
        // What the CLI's explain decoder sends for `jk explain -m a` with no other flags.
        ExplainRequest cli = new ExplainRequest(
                dir,
                JkDirs.cache().toString(),
                0,
                false,
                null,
                JkDirs.jdks().toString(),
                false,
                true,
                false,
                false,
                0,
                List.of("a"),
                TestSelection.DEFAULT,
                false);
        assertThat(Forecasts.request(Map.of("dir", dir, "modules", "a"), null, Set.of()))
                .isEqualTo(cli);
        Map<String, Object> wire = Forecasts.structured(Forecasts.forecast(
                cli, ProtoSession.sessionOf(cli.encode(), Session.CancelToken.NONE), (s, d, t, l) -> {}));

        HttpResponse<String> rest =
                get("/api/explain?dir=" + URLEncoder.encode(dir, StandardCharsets.UTF_8) + "&modules=a");
        assertThat(rest.statusCode()).as(rest.body()).isEqualTo(200);
        Map<String, Object> restBody = (Map<String, Object>) MiniJson.parse(rest.body());

        Map<String, Object> result =
                new ExplainTool().call(new McpCall(null, Map.of("dir", dir, "modules", List.of("a")), null));
        Map<String, Object> mcp = (Map<String, Object>) result.get("structuredContent");

        assertThat(rows(wire)).containsExactly(ws.resolve("a").toString());
        assertThat(rows(restBody)).isEqualTo(rows(wire));
        assertThat(rows(mcp)).isEqualTo(rows(wire));
        for (String key : List.of("dirtyCount", "etaMillis", "fullMillis", "etaLowMillis", "etaHighMillis")) {
            assertThat(num(restBody, key)).as("REST " + key).isEqualTo(num(wire, key));
            assertThat(num(mcp, key)).as("MCP " + key).isEqualTo(num(wire, key));
        }
    }

    @Test
    void a_field_the_forecast_cannot_honour_is_refused_by_name() throws Exception {
        workspace();
        HttpResponse<String> rest =
                get("/api/explain?dir=" + URLEncoder.encode(ws.toString(), StandardCharsets.UTF_8) + "&deadline_s=5");
        assertThat(rest.statusCode()).isEqualTo(400);
        assertThat(rest.body()).contains("unknown field deadline_s");
    }

    private void workspace() throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : List.of("a", "b")) {
            Path mod = Files.createDirectories(ws.resolve(m));
            Files.writeString(
                    mod.resolve("jk.toml"), "group = \"t\"\nname = \"" + m + "\"\nversion = \"0.1.0\"\njava = 25\n");
            Path src = Files.createDirectories(mod.resolve("src/main/java/t/" + m));
            Files.writeString(src.resolve("C.java"), "package t." + m + ";\npublic class C {}\n");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Map<String, Object> forecast) {
        assertThat(forecast).as(String.valueOf(forecast)).containsKey("modules");
        return ((List<Map<String, Object>>) forecast.get("modules"))
                .stream().map(r -> r.get("dir")).toList();
    }

    private static long num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }
}
