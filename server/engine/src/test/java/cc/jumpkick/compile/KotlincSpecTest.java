// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlincSpecTest {

    /**
     * the AOT trainer runs on the PROJECT's kotlinc worker classpath, and older Kotlin
     * lines reject unknown JVM targets ("Unknown JVM target: 25"). The trainer spec must carry the
     * request's own jvmTarget — the value that project's Kotlin provably accepts — never a
     * hardcoded host-side constant.
     */
    @Test
    void trainer_spec_uses_the_requests_jvm_target(@TempDir Path tempDir) throws IOException {
        Path scratch = Files.createDirectories(tempDir.resolve("scratch"));
        KotlincRequest request = requestWithJvmTarget(tempDir, 17); // oldest supported line

        List<String> cmd = KotlincSpec.trainerCommand(
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

        KotlincSpec.trainerCommandForOptimize(
                tempDir.resolve("java-home"), "worker.jar", tempDir.resolve("out.aot"), scratch);

        String spec = Files.readString(scratch.resolve("train.spec"), StandardCharsets.UTF_8);
        assertThat(jvmTargetIn(spec)).isEqualTo(String.valueOf(KotlincSpec.TRAINER_FALLBACK_JVM_TARGET));
        // Intent pin: 21 is the newest target every supported Kotlin line accepts. Do not bump
        // this alongside the host JDK — pre-2.2.20 Kotlin rejects newer targets and the AOT
        // cache silently never trains.
        assertThat(KotlincSpec.TRAINER_FALLBACK_JVM_TARGET).isEqualTo(21);
    }

    /**
     * The pairing the cache depends on: whatever JDK reaches kotlinc as {@code -jdk-home} is the
     * JDK {@link ActionKey#forKotlinc} hashes. Break either half and a project that switches
     * {@code jdk = 17} to {@code 21} silently restores bytecode linked against 17.
     */
    @Test
    void jdk_home_reaches_kotlinc_and_the_action_key_together(@TempDir Path tempDir) throws IOException {
        Path jdk17 = jdkHome(tempDir.resolve("temurin-17"), "17.0.12+7");
        Path jdk21 = jdkHome(tempDir.resolve("temurin-21"), "21.0.5+11");
        KotlincRequest on17 = requestWithJavaHome(tempDir, jdk17);
        KotlincRequest on21 = requestWithJavaHome(tempDir, jdk21);

        assertThat(jdkHomeArg(on17))
                .isEqualTo(jdk17.toAbsolutePath().normalize().toString());
        assertThat(jdkHomeArg(on21))
                .isEqualTo(jdk21.toAbsolutePath().normalize().toString());
        // jvmTarget is identical on both requests — only -jdk-home moved.
        assertThat(on17.jvmTarget()).isEqualTo(on21.jvmTarget());
        assertThat(ActionKey.forKotlinc("compile-kotlin", on17, "0.1.0"))
                .isNotEqualTo(ActionKey.forKotlinc("compile-kotlin", on21, "0.1.0"));
    }

    /** The value the spec hands the compiler after {@code -jdk-home}. */
    private static String jdkHomeArg(KotlincRequest request) throws IOException {
        Path spec = KotlincSpec.write(request);
        try {
            List<String> args = PluginSpec.read(spec).args();
            int at = args.indexOf("-jdk-home");
            assertThat(at).as("-jdk-home in %s", args).isNotNegative();
            return args.get(at + 1);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static Path jdkHome(Path home, String version) throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    private static KotlincRequest requestWithJavaHome(Path tempDir, Path javaHome) throws IOException {
        Path source = tempDir.resolve("Main.kt");
        if (!Files.exists(source)) Files.writeString(source, "fun main() {}");
        Path worker = tempDir.resolve("worker.jar");
        if (!Files.exists(worker)) Files.writeString(worker, "worker");
        return KotlincRequest.builder()
                .sources(List.of(source))
                .outputDir(tempDir.resolve("classes"))
                .jvmTarget(17)
                .workerClasspath(List.of(worker))
                .javaHome(javaHome)
                .build();
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
