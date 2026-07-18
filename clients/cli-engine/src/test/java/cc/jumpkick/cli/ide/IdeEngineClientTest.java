// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.ide;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ProjectInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ticket-1014: IdeEngineClient facade against the in-process engine seam ({@code jk.test.noEngine}).
 */
class IdeEngineClientTest {

    @BeforeAll
    static void useInProcessEngine() {
        System.setProperty("jk.test.noEngine", "true");
    }

    @Test
    void projectInfo_and_connect_work_without_shelling_out(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(
                project.resolve("jk.toml"),
                """
                [project]
                name = "app"
                group = "com.example"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        IdeEngineClient ide = IdeEngineClient.open(project, cache, null);
        var handshake = ide.connect();
        assertThat(handshake.version()).isNotBlank();

        ProjectInfo info = ide.projectInfo();
        assertThat(info.error()).isNull();
        assertThat(info.name()).isEqualTo("app");
        assertThat(info.group()).isEqualTo("com.example");
    }

    @Test
    void sync_fires_progress_callbacks(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(
                project.resolve("jk.toml"),
                """
                [project]
                name = "app"
                group = "com.example"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        List<String> steps = new ArrayList<>();
        AtomicBoolean finished = new AtomicBoolean();
        IdeEngineClient ide = IdeEngineClient.open(project, cache, null);
        ide.connect();
        var outcome = ide.sync(new IdeEngineClient.ProgressListener() {
            @Override
            public void onStepStart(String step, String phase) {
                steps.add("start:" + step);
            }

            @Override
            public void onStepFinish(String step, boolean success, String status) {
                steps.add("finish:" + step + ":" + success);
                finished.set(true);
            }
        });

        assertThat(finished).isTrue();
        assertThat(steps).anyMatch(s -> s.startsWith("start:"));
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void build_listener_sees_module_boundaries(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(
                project.resolve("jk.toml"),
                """
                [project]
                name = "app"
                group = "com.example"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        List<String> events = new ArrayList<>();
        IdeEngineClient ide = IdeEngineClient.open(project, cache, null);
        ide.connect();
        var outcome = ide.build(new IdeEngineClient.BuildListener() {
            @Override
            public void onModuleStart(String coord, Path dir) {
                events.add("module-start:" + coord);
            }

            @Override
            public void onModuleFinish(String coord, boolean success) {
                events.add("module-finish:" + coord + ":" + success);
            }

            @Override
            public void onStepStart(String step, String phase) {
                events.add("step:" + step);
            }
        });

        assertThat(outcome.success()).isTrue();
        assertThat(events).anyMatch(e -> e.startsWith("module-start:"));
        assertThat(events).anyMatch(e -> e.startsWith("module-finish:"));
        assertThat(events).anyMatch(e -> e.startsWith("step:"));
    }
}
