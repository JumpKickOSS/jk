// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A checkout install moves the engine pointer only when it leaves no released PATH client older
 * than that engine: it installs the client first, and refuses the engine when the client stayed
 * an older release (no native client built, or only the engine module selected).
 */
class InstallPathClientTest {

    @Test
    void an_older_released_path_client_the_pass_did_not_replace_refuses_the_engine(@TempDir Path bin) throws Exception {
        Path client = Files.writeString(EngineInstall.pathClient(bin), "released client");

        assertThatThrownBy(() -> InstallOrder.refuseOlderPathClient(bin, "0.51.0-SNAPSHOT", c -> Optional.of("0.50.5")))
                .hasMessageContaining("not installing engine 0.51.0-SNAPSHOT")
                .hasMessageContaining("jk 0.50.5");
        assertThat(client).exists();
    }

    @Test
    void a_path_client_as_new_as_the_engine_a_snapshot_or_none_lets_the_engine_in(@TempDir Path bin) throws Exception {
        assertThatCode(() -> InstallOrder.refuseOlderPathClient(bin, "0.51.0-SNAPSHOT", c -> Optional.of("0.50.5")))
                .as("no PATH client")
                .doesNotThrowAnyException();
        Files.writeString(EngineInstall.pathClient(bin), "client");
        for (String path : List.of("0.51.0-SNAPSHOT", "0.50.5-SNAPSHOT", "0.51.0", "0.52.0")) {
            assertThatCode(() -> InstallOrder.refuseOlderPathClient(bin, "0.51.0-SNAPSHOT", c -> Optional.of(path)))
                    .as(path)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> InstallOrder.refuseOlderPathClient(bin, "0.51.0", c -> Optional.empty()))
                .as("a client that does not say")
                .doesNotThrowAnyException();
    }

    @Test
    void the_path_client_installs_before_the_engine() {
        ProjectInfo engine = ProjectInfo.decode("{\"productLib\":\"jk-engine\"}");
        ProjectInfo library = ProjectInfo.decode("{\"name\":\"lib\"}");
        ProjectInfo client = ProjectInfo.decode("{\"productBin\":\"jk\"}");
        List<ProjectInfo> order = new ArrayList<>(List.of(engine, library, client));

        order.sort(Comparator.comparingInt(InstallOrder::rank));

        assertThat(order).containsExactly(client, library, engine);
    }
}
