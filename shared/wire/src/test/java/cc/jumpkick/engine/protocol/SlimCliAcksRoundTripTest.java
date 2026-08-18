// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trips for the JK-2136 acks that shipped without them (JK-2171). The hand-rolled
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

        ModuleGraphAck err = ModuleGraphAck.decode(ModuleGraphAck.error("no \"jk.toml\"\nhere").encode());
        assertThat(err.error()).isEqualTo("no \"jk.toml\"\nhere");
    }

    @Test
    void new_project_ack_round_trips() {
        NewProjectAck in = NewProjectAck.of("/home/u/my proj", "com.example:x", 17);
        NewProjectAck out = NewProjectAck.decode(in.encode());
        assertThat(out).isEqualTo(in);
        assertThat(out.error()).isNull();

        NewProjectAck err = NewProjectAck.decode(NewProjectAck.error("target directory is not empty").encode());
        assertThat(err.error()).isEqualTo("target directory is not empty");
        assertThat(err.filesWritten()).isZero();
    }

    @Test
    void plugin_install_local_ack_round_trips() {
        PluginInstallLocalAck in = new PluginInstallLocalAck(
                null,
                2,
                1,
                List.of("plugins/foo/target/foo-1.0.jar"),
                List.of("installed foo 1.0", "skipped bar (\"stale\")"));
        PluginInstallLocalAck out = PluginInstallLocalAck.decode(in.encode());
        assertThat(out).isEqualTo(in);

        PluginInstallLocalAck err =
                PluginInstallLocalAck.decode(PluginInstallLocalAck.error("plugin install-local request names no dir")
                        .encode());
        assertThat(err.error()).contains("names no dir");
        assertThat(err.installed()).isZero();
    }
}
