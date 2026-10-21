// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.host.ManifestNames;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphTrainingTest {

    @Test
    void the_trainer_records_while_it_queries_a_workspace_of_every_kind(@TempDir Path tmp) throws Exception {
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        Path aot = tmp.resolve("graph.aot");

        List<String> cmd = GraphTraining.command(tmp.resolve("java-home"), "worker.jar", aot, scratch);

        assertThat(cmd).contains("-XX:AOTCacheOutput=" + aot, PluginLoader.WORKER_MAIN);
        Path root = scratch.resolve("workspace");
        String spec = Files.readString(scratch.resolve("train.spec"), StandardCharsets.UTF_8);
        assertThat(spec).contains("query", "Greeter greet", root.toString());
        assertThat(cmd.getLast())
                .isEqualTo(scratch.resolve("train.spec").toAbsolutePath().toString());
        assertThat(root.resolve(ManifestNames.MANIFEST)).isRegularFile();
        assertThat(root.resolve("src/main/java/demo/Greeter.java")).isRegularFile();
        assertThat(root.resolve("web/app.ts")).isRegularFile();
        assertThat(root.resolve("README.md")).isRegularFile();
    }
}
