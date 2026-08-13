// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatAotTrainerTest {

    @Test
    void trainer_records_aot_and_formats_synthetic_sources(@TempDir Path tmp) throws Exception {
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        Path aot = tmp.resolve("formatter.aot");
        Path palantir = tmp.resolve("palantir.jar");
        Path ktfmt = tmp.resolve("ktfmt.jar");
        Files.writeString(palantir, "fake");
        Files.writeString(ktfmt, "fake");

        List<String> cmd = FormatPlans.trainerCommand(
                tmp.resolve("java-home"),
                "worker.jar",
                aot,
                scratch,
                "palantir",
                "kotlinlang",
                List.of(palantir),
                List.of(),
                List.of(ktfmt),
                true,
                true,
                true);

        assertThat(cmd).contains("-XX:AOTCacheOutput=" + aot);
        assertThat(cmd).contains(PluginLoader.WORKER_MAIN);
        assertThat(cmd.stream().anyMatch(s -> s.contains("add-exports=jdk.compiler")))
                .isTrue();

        String spec = Files.readString(scratch.resolve("train.spec"), StandardCharsets.UTF_8);
        assertThat(spec).contains("Hello.java");
        assertThat(spec).contains("Hello.kt");
        assertThat(spec).contains("palantir");
        assertThat(Files.isRegularFile(scratch.resolve("Hello.java"))).isTrue();
        assertThat(Files.isRegularFile(scratch.resolve("Hello.kt"))).isTrue();
    }
}
