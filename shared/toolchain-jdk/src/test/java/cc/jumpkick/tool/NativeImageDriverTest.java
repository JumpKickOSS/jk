// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeImageDriverTest {

    private static final Path BIN = Path.of("/opt/graalvm/bin/native-image");

    @Test
    void executable_command_ends_with_the_main_class_and_has_no_shared_flag() {
        var req = new NativeImageDriver.Request(
                Path.of("/opt/graalvm"),
                List.of(Path.of("app.jar")),
                "com.example.Main",
                Path.of("target/widget"),
                List.of("--verbose"));
        List<String> cmd = NativeImageDriver.buildCommand(BIN, req);

        assertThat(cmd).doesNotContain("--shared");
        assertThat(cmd).contains("--no-fallback", "--verbose");
        assertThat(cmd.getLast()).isEqualTo("com.example.Main");
        assertThat(cmd)
                .containsSequence(
                        "-o", Path.of("target/widget").toAbsolutePath().toString());
    }

    @Test
    void shared_library_command_has_shared_flag_and_no_main_class() {
        var req = new NativeImageDriver.Request(
                Path.of("/opt/graalvm"),
                List.of(Path.of("app.jar")),
                null,
                Path.of("target/libwidget"),
                List.of(), /*shared*/
                true);
        List<String> cmd = NativeImageDriver.buildCommand(BIN, req);

        assertThat(cmd).contains("--shared");
        // No trailing main class: the command ends at the output / fixed flags.
        assertThat(cmd).doesNotContain("com.example.Main");
        assertThat(cmd.getLast()).isEqualTo("--no-fallback");
        assertThat(cmd)
                .containsSequence(
                        "-o", Path.of("target/libwidget").toAbsolutePath().toString());
    }

    @Test
    void executable_request_requires_a_main_class() {
        assertThat(catchThrowable(() -> new NativeImageDriver.Request(
                        Path.of("/opt/graalvm"), List.of(), null, Path.of("target/x"), List.of())))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void step_header_normalizes_trailing_ascii_dots_to_ellipsis() {
        // Graal prints "[N/M] Label..." — live TUI labels use the same … as the rest of jk.
        var step = NativeImageDriver.parseStepHeader("[1/8] Initializing...");
        assertThat(step).isNotNull();
        assertThat(step.current()).isEqualTo(1);
        assertThat(step.total()).isEqualTo(8);
        assertThat(step.label()).isEqualTo("Initializing…");

        assertThat(NativeImageDriver.parseStepHeader("[2/8] Performing analysis...")
                        .label())
                .isEqualTo("Performing analysis…");
        assertThat(NativeImageDriver.parseStepHeader("[5/8] Inlining methods...")
                        .label())
                .isEqualTo("Inlining methods…");
        // Timing columns after two+ spaces are dropped; trailing ... still normalized.
        assertThat(NativeImageDriver.parseStepHeader("[3/8] Building universe...      (1.2s @ 0.40GB)")
                        .label())
                .isEqualTo("Building universe…");
        // Already-unicode ellipsis stays; non-headers are ignored.
        assertThat(NativeImageDriver.parseStepHeader("[8/8] Creating image…").label())
                .isEqualTo("Creating image…");
        assertThat(NativeImageDriver.parseStepHeader("not a step")).isNull();
    }

    @Test
    void normalize_step_label_rewrites_only_trailing_ascii_dots() {
        assertThat(NativeImageDriver.normalizeStepLabel("Initializing...")).isEqualTo("Initializing…");
        assertThat(NativeImageDriver.normalizeStepLabel("done")).isEqualTo("done");
        assertThat(NativeImageDriver.normalizeStepLabel("already…")).isEqualTo("already…");
        assertThat(NativeImageDriver.normalizeStepLabel("")).isEmpty();
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
