// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JupiterParallelDetectTest {

    @Test
    void detects_properties_file_on_directory_classpath(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("junit-platform.properties"),
                "junit.jupiter.execution.parallel.enabled=true\n",
                StandardCharsets.UTF_8);
        assertThat(JupiterParallelDetect.enabled(List.of(dir))).isTrue();
    }

    @Test
    void false_when_property_disabled(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("junit-platform.properties"),
                "junit.jupiter.execution.parallel.enabled=false\n",
                StandardCharsets.UTF_8);
        assertThat(JupiterParallelDetect.enabled(List.of(dir))).isFalse();
    }

    @Test
    void false_when_no_properties(@TempDir Path dir) {
        assertThat(JupiterParallelDetect.enabled(List.of(dir))).isFalse();
    }

    @Test
    void stack_warning_mentions_workers() {
        assertThat(JupiterParallelDetect.stackWarning(4)).contains("workers=4").contains("jupiter");
    }
}
