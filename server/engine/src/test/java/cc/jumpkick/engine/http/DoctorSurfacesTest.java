// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.doctor.DoctorChecks;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.tools.DoctorTool;
import cc.jumpkick.jsonl.MiniJson;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One doctor report: {@code GET /api/doctor} and MCP {@code doctor} answer the same rows {@code jk
 * doctor} checks, a project with no lock is the same {@code lock} warning on both, and the rows only
 * the client can answer are listed as unavailable rather than left out.
 */
@Tag("integration")
@SuppressWarnings({"NullAway", "unchecked"})
class DoctorSurfacesTest extends HttpEngineServerHarness {

    @TempDir
    Path app;

    @Test
    void rest_and_mcp_answer_the_same_rows_and_mark_the_clients_own_unavailable() throws Exception {
        Files.writeString(app.resolve("jk.toml"), "group = \"g\"\nname = \"app\"\nversion = \"1\"\n");

        HttpResponse<String> rest = get("/api/doctor?dir=" + URLEncoder.encode(app.toString(), StandardCharsets.UTF_8));
        assertThat(rest.statusCode()).as(rest.body()).isEqualTo(200);
        Map<String, Object> restBody = (Map<String, Object>) MiniJson.parse(rest.body());

        Map<String, Object> result = new DoctorTool().call(new McpCall(null, Map.of("dir", app.toString()), null));
        Map<String, Object> mcp = new LinkedHashMap<>((Map<String, Object>) result.get("structuredContent"));
        for (String key : List.of("schema", "type", "truncated", "next")) mcp.remove(key);

        assertThat(MiniJson.parse(MiniJson.write(mcp))).isEqualTo(restBody);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Object row : (List<Object>) restBody.get("rows")) {
            Map<String, Object> r = (Map<String, Object>) row;
            byId.put(String.valueOf(r.get("id")), r);
        }
        assertThat(byId.get("lock")).containsEntry("status", "warn").containsEntry("fix", "run jk lock");
        assertThat(byId.get("lock").get("status"))
                .as("the row jk doctor prints for the same project")
                .isEqualTo(DoctorChecks.lock(app).status().wire());
        assertThat(byId).containsKeys("config", "dirs", "state", "jdk", "m2", "engine");
        for (String id : DoctorChecks.CLIENT_LOCAL) {
            assertThat(byId.get(id)).as(id).containsEntry("status", "unavailable");
        }
    }
}
