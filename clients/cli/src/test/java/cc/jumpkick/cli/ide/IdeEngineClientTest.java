// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.ide;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.engine.protocol.ProjectInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IdeEngineClient facade over the real engine wire path. */
@Tag("integration")
class IdeEngineClientTest {

    @BeforeAll
    static void materializeEngine() {
        EngineTestSupport.ensureEngineMaterialized();
    }

    @Test
    void projectInfo_and_connect_work_without_shelling_out(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
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
        Files.writeString(project.resolve("jk.toml"), """
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
        Files.writeString(project.resolve("jk.toml"), """
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

    @Test
    void testModule_runs_against_wire(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name = "app"
                group = "com.example"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), """
                package com.example;
                public class App {
                  public static int one() { return 1; }
                }
                """);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        List<String> events = new ArrayList<>();
        IdeEngineClient ide = IdeEngineClient.open(project, cache, null);
        ide.connect();
        var outcome = ide.testModule(null, new IdeEngineClient.BuildListener() {
            @Override
            public void onModuleStart(String coord, Path dir) {
                events.add("start:" + coord);
            }

            @Override
            public void onModuleFinish(String coord, boolean success) {
                events.add("finish:" + coord + ":" + success);
            }
        });
        assertThat(events).anyMatch(e -> e.startsWith("start:"));
        assertThat(events).anyMatch(e -> e.startsWith("finish:"));
        // No tests is still a successful test plan (nothing failed).
        assertThat(outcome.modules()).isEqualTo(1);
    }
}
