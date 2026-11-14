// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProtoSession;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A request carries the names its own project declares whether or not the verb described the
 * project first: {@code jk bsp}, {@code jk verify} and plugin commands never ask, and {@code jk
 * explain} drops what it asked once a lock refresh forgets it, yet a module's {@code [env] require}
 * name exported in the shell must reach the engine on every one of them.
 */
@Tag("integration")
class EngineWireShellE2eTest {

    private static final String NAME = "MY_DECLARED_PROBE_TOKEN";

    @AfterEach
    void clear() {
        System.clearProperty("jk.env." + NAME);
        ProjectInfos.forget();
    }

    @Test
    void a_request_carries_its_projects_declared_names_with_nothing_described_before_it(@TempDir Path project)
            throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "declares"
                version = "1.0.0"
                java    = 25

                [env]
                require = ["%s"]
                """.formatted(NAME));
        assertThat(run("lock", "-C", project.toString()))
                .as("lock, which starts the engine")
                .isZero();
        System.setProperty("jk.env." + NAME, "exported-value");
        String request = "{\"type\":\"build\",\"dir\":\"" + project.toString().replace("\\", "\\\\") + "\"}";

        ProjectInfos.forget();
        assertThat(ProtoSession.shellOf(EngineWire.withShell(request)))
                .as("nothing described this project in this run")
                .containsEntry(NAME, "exported-value");

        ProjectInfos.forget();
        assertThat(ProtoSession.shellOf(EngineWire.withShell(request)))
                .as("after a lock refresh forgot the summaries")
                .containsEntry(NAME, "exported-value");
    }
}
