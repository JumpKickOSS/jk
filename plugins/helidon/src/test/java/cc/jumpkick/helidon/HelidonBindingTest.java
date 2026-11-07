// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import io.helidon.service.maven.plugin.JkCreateApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker's half of the binding step: when it forks Helidon's generators, and how it reads what
 * the fork says. The fork itself runs Helidon 27 (Java 27 bytecode) and is covered end to end by
 * the engine's {@code HelidonQuickstartE2eTest}.
 */
class HelidonBindingTest {

    private static final String PREFIX = JkCreateApplication.PREFIX;

    @Test
    void a_class_file_carrying_generate_binding_is_found(@TempDir Path tmp) throws Exception {
        Path pkg = Files.createDirectories(tmp.resolve("com/example"));
        Files.write(pkg.resolve("Service.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, 1, 2, 3});
        assertThat(HelidonBinding.carriesGenerateBinding(tmp)).isFalse();

        byte[] annotated =
                ("junk Lio/helidon/service/registry/Service$GenerateBinding; more").getBytes(StandardCharsets.UTF_8);
        Files.write(pkg.resolve("Main.class"), annotated);
        assertThat(HelidonBinding.carriesGenerateBinding(tmp)).isTrue();
    }

    @Test
    void the_step_is_registered_unless_binding_is_off_and_no_main_is_asked_for() {
        assertThat(settings(Map.of()).bindingStep())
                .as("unset: the classes decide")
                .isTrue();
        assertThat(settings(Map.of("binding", false)).bindingStep()).isFalse();
        assertThat(settings(Map.of("binding", false, "generate-main", true)).bindingStep())
                .isTrue();
    }

    @Test
    void a_failure_carries_helidon_s_words_and_each_finding() {
        HelidonBinding.Report report = new HelidonBinding.Report();
        report.accept("2026.11.07 INFO some framework log line");
        report.accept(PREFIX + "WARNING WARN class ApplicationValidator: Injection of request scoped service"
                + " into a singleton without a supplier.");
        report.accept(PREFIX + "ERROR Compilation error encountered:\\nApplicationBinding.java:3: error");
        report.accept(PREFIX + "FAILED Application validation failed, see log output for details.");

        String failure = report.failure(1);

        assertThat(failure)
                .startsWith("Helidon's create-application failed: Application validation failed")
                .contains("Injection of request scoped service into a singleton without a supplier")
                .contains("Compilation error encountered:\nApplicationBinding.java:3: error")
                .as("the fork said why; its log is not repeated")
                .doesNotContain("some framework log line");
    }

    @Test
    void the_generated_types_name_the_label() {
        HelidonBinding.Report report = new HelidonBinding.Report();
        report.accept(PREFIX + "BINDING com.example.ApplicationBinding");
        report.accept(PREFIX + "MAIN com.example.ApplicationMain");
        report.accept(PREFIX + "INFO Generating application binding");
        assertThat(report.generated).containsExactly("ApplicationBinding", "ApplicationMain");
        assertThat(report.warnings).isEmpty();
    }

    @Test
    void a_failure_that_said_nothing_shows_the_fork_s_tail() {
        HelidonBinding.Report report = new HelidonBinding.Report();
        report.accept("Error: Could not find or load main class");
        assertThat(report.failure(1)).contains("(exit 1)").contains("Could not find or load main class");
    }

    private static HelidonSettings settings(Map<String, Object> values) {
        Map<String, Object> all = new HashMap<>(values);
        all.put("version", "27.0.1");
        return HelidonSettings.of(new PluginConfig("helidon", all));
    }
}
