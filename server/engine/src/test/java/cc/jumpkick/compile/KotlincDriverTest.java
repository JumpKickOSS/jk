// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlincDriverTest {

    /**
     * JK-1434: the AOT trainer runs on the PROJECT's kotlinc worker classpath, and older Kotlin
     * lines reject unknown JVM targets ("Unknown JVM target: 25"). The trainer spec must carry the
     * request's own jvmTarget — the value that project's Kotlin provably accepts — never a
     * hardcoded host-side constant.
     */
    @Test
    void trainer_spec_uses_the_requests_jvm_target(@TempDir Path tempDir) throws IOException {
        Path scratch = Files.createDirectories(tempDir.resolve("scratch"));
        KotlincRequest request = requestWithJvmTarget(tempDir, 17); // oldest supported line

        List<String> cmd = KotlincDriver.trainerCommand(
                request, "worker.jar", tempDir.resolve("java-home"), tempDir.resolve("out.aot"), scratch);

        assertThat(cmd).isNotEmpty();
        String spec = Files.readString(scratch.resolve("train.spec"), StandardCharsets.UTF_8);
        assertThat(spec).contains("\"key\":\"jvmTarget\"");
        assertThat(jvmTargetIn(spec)).isEqualTo("17");
    }

    @Test
    void bare_optimize_trainer_falls_back_to_a_target_every_supported_kotlin_accepts(@TempDir Path tempDir)
            throws IOException {
        Path scratch = Files.createDirectories(tempDir.resolve("scratch"));

        KotlincDriver.trainerCommandForOptimize(
                tempDir.resolve("java-home"), "worker.jar", tempDir.resolve("out.aot"), scratch);

        String spec = Files.readString(scratch.resolve("train.spec"), StandardCharsets.UTF_8);
        assertThat(jvmTargetIn(spec))
                .isEqualTo(String.valueOf(KotlincDriver.TRAINER_FALLBACK_JVM_TARGET));
        // Intent pin: 21 is the newest target every supported Kotlin line accepts. Do not bump
        // this alongside the host JDK — pre-2.2.20 Kotlin rejects newer targets and the AOT
        // cache silently never trains (JK-1434).
        assertThat(KotlincDriver.TRAINER_FALLBACK_JVM_TARGET).isEqualTo(21);
    }

    private static String jvmTargetIn(String spec) {
        return spec.lines()
                .filter(l -> l.contains("\"key\":\"jvmTarget\""))
                .map(l -> l.replaceAll(".*\"value\":\"([^\"]+)\".*", "$1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no jvmTarget config line in spec:\n" + spec));
    }

    private static KotlincRequest requestWithJvmTarget(Path tempDir, int jvmTarget) {
        return new KotlincRequest(
                List.of(tempDir.resolve("Main.kt")),
                List.of(tempDir.resolve("kotlin-stdlib.jar")),
                tempDir.resolve("classes"),
                jvmTarget,
                List.of(tempDir.resolve("worker.jar")),
                tempDir.resolve("project-jdk"),
                null,
                null,
                List.of(),
                List.of(),
                null);
    }
}
