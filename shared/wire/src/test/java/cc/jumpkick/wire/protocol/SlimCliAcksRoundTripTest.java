// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trips for the acks that shipped without them. The hand-rolled
 * JSONL codec makes a per-ack round-trip the cheap guard this layer relies on.
 */
class SlimCliAcksRoundTripTest {

    @Test
    void module_graph_ack_round_trips_newline_and_quote_heavy_graphs() {
        String dot = "digraph modules {\n  \"com.example:app\" -> \"com.example:lib\";\n  // says \"hi\"\\path\n}\n";
        ModuleGraphAck in = ModuleGraphAck.of(dot);
        ModuleGraphAck out = ModuleGraphAck.decode(in.encode());
        assertThat(out.graph()).isEqualTo(dot);
        assertThat(out.error()).isNull();

        ModuleGraphAck err = ModuleGraphAck.decode(
                ModuleGraphAck.error("no \"jk.toml\"\nhere").encode());
        assertThat(err.error()).isEqualTo("no \"jk.toml\"\nhere");
    }

    @Test
    void new_project_ack_round_trips() {
        NewProjectAck in = NewProjectAck.of("/home/u/my proj", "com.example:x", 17);
        NewProjectAck out = NewProjectAck.decode(in.encode());
        assertThat(out).isEqualTo(in);
        assertThat(out.error()).isNull();

        NewProjectAck err = NewProjectAck.decode(
                NewProjectAck.error("target directory is not empty").encode());
        assertThat(err.error()).isEqualTo("target directory is not empty");
        assertThat(err.filesWritten()).isZero();
    }

    @Test
    void new_project_ack_round_trips_the_agent_wiring_in_order() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("/p/AGENTS.md", "Wrote");
        files.put("/p/.mcp.json", "Wrote");
        NewProjectAck in = NewProjectAck.of("/p", "id", 3, files, List.of("trust this folder"));
        NewProjectAck out = NewProjectAck.decode(in.encode());
        assertThat(out).isEqualTo(in);
        assertThat(out.agentFiles().keySet()).containsExactly("/p/AGENTS.md", "/p/.mcp.json");
        assertThat(NewProjectAck.of("/p", "id", 3).encode()).doesNotContain("agent");
    }

    @Test
    void new_project_request_round_trips_the_working_directory_only_when_set() {
        NewProjectRequest in = new NewProjectRequest(
                "demo",
                "/home/u",
                null,
                null,
                "auto",
                "./my.g8",
                true,
                Map.of("java", "21"),
                true,
                null,
                "/home/u/work",
                "detect",
                List.of("claude", "codex"));
        assertThat(NewProjectRequest.decode(in.encode())).isEqualTo(in);

        NewProjectRequest bare = new NewProjectRequest(
                "demo", "/home/u", null, null, null, null, true, Map.of(), false, null, null, null, List.of());
        assertThat(bare.encode()).doesNotContain("\"cwd\"").doesNotContain("agent");
        assertThat(NewProjectRequest.decode(bare.encode()).cwd()).isNull();
    }
}
