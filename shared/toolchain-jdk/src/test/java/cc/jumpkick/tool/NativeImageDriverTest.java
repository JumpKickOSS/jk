// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeImageDriverTest {

    private static final Path BIN = Path.of("/opt/graalvm/bin/native-image");

    @Test
    void the_not_found_message_names_every_home_the_search_looked_in_and_why() {
        Path resolved = Path.of("/jdks/graalvm-25");
        Path project = Path.of("/jdks/temurin-25.0.4.1");

        String message = requireNonNull(NativeImageDriver.notFoundError(List.of(
                        new NativeImageDriver.Candidate("the GraalVM the client resolved", resolved),
                        new NativeImageDriver.Candidate("the JDK this project builds with", project)))
                .getMessage());

        // The reported failure named only the last tier's home, so the GraalVM that was resolved
        // and rejected — the whole explanation — was the one home missing from it.
        assertThat(message).contains(resolved.toString(), "the GraalVM the client resolved");
        assertThat(message).contains(project.toString(), "the JDK this project builds with");
        assertThat(message.indexOf(resolved.toString()))
                .as("in the order the search tried them")
                .isLessThan(message.indexOf(project.toString()));
        assertThat(message).contains("$GRAALVM_HOME", "PATH", "jk jdk install graalvm-25");
    }

    @Test
    void a_home_two_tiers_both_named_is_printed_once() {
        Path one = Path.of("/jdks/temurin-25");
        String message = requireNonNull(NativeImageDriver.notFoundError(List.of(
                        new NativeImageDriver.Candidate("the client's answer", one),
                        new NativeImageDriver.Candidate("the project's JDK", one)))
                .getMessage());

        assertThat(message.split(Pattern.quote(one.toString()), -1))
                .as("one line per home: the same directory twice reads like two searches")
                .hasSize(2);
        assertThat(message).contains("the client's answer").doesNotContain("the project's JDK");
    }

    @Test
    void an_unlabelled_home_is_named_without_an_origin() {
        String message = requireNonNull(
                NativeImageDriver.notFoundError(Path.of("/jdks/temurin-25")).getMessage());

        assertThat(message).contains(Path.of("/jdks/temurin-25").toString());
        assertThat(message).doesNotContain(" — ");
    }

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
    @SuppressWarnings("NullAway") // the null is deliberate: an executable request must refuse it
    void executable_request_requires_a_main_class() {
        assertThat(catchThrowable(() -> new NativeImageDriver.Request(
                        Path.of("/opt/graalvm"), List.of(), null, Path.of("target/x"), List.of())))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void step_header_normalizes_trailing_ascii_dots_to_ellipsis() {
        // Graal prints "[N/M] Label..." — live TUI labels use the same … as the rest of jk.
        var step = requireNonNull(NativeImageDriver.parseStepHeader("[1/8] Initializing..."));
        assertThat(step.current()).isEqualTo(1);
        assertThat(step.total()).isEqualTo(8);
        assertThat(step.label()).isEqualTo("Initializing…");

        assertThat(requireNonNull(NativeImageDriver.parseStepHeader("[2/8] Performing analysis..."))
                        .label())
                .isEqualTo("Performing analysis…");
        assertThat(requireNonNull(NativeImageDriver.parseStepHeader("[5/8] Inlining methods..."))
                        .label())
                .isEqualTo("Inlining methods…");
        // Timing columns after two+ spaces are dropped; trailing ... still normalized.
        assertThat(requireNonNull(NativeImageDriver.parseStepHeader("[3/8] Building universe...      (1.2s @ 0.40GB)"))
                        .label())
                .isEqualTo("Building universe…");
        // Already-unicode ellipsis stays; non-headers are ignored.
        assertThat(requireNonNull(NativeImageDriver.parseStepHeader("[8/8] Creating image…"))
                        .label())
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

    @Test
    void with_arg_file_rewrites_to_at_file_and_preserves_args(@TempDir Path tmp) throws Exception {
        Path argFile = tmp.resolve("args.txt");
        List<String> longCmd = new ArrayList<>();
        longCmd.add(BIN.toString());
        longCmd.add("-cp");
        longCmd.add("a.jar" + File.pathSeparator + "b.jar");
        longCmd.add("-o");
        longCmd.add(tmp.resolve("out").toString());
        longCmd.add("com.example.Main");

        List<String> rewritten = NativeImageDriver.withArgFile(BIN, longCmd, argFile);
        assertThat(rewritten).containsExactly(BIN.toString(), "@" + argFile.toAbsolutePath());
        String body = Files.readString(argFile);
        assertThat(body.lines())
                .containsExactly(
                        "-cp",
                        "a.jar" + File.pathSeparator + "b.jar",
                        "-o",
                        tmp.resolve("out").toString(),
                        "com.example.Main");
    }

    @Test
    void arg_file_is_used_only_past_the_command_line_cap() {
        List<String> shortCmd = List.of(BIN.toString(), "-cp", "app.jar", "-o", "out", "com.example.Main");
        List<String> longCmd = new ArrayList<>(List.of(BIN.toString(), "-cp"));
        longCmd.add("C:\\repo\\libs\\artifact-1.2.3.jar;".repeat(300));

        String saved = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(NativeImageDriver.needsArgFile(shortCmd)).isFalse();
            assertThat(NativeImageDriver.needsArgFile(longCmd)).isTrue();
            // POSIX has no command-line cap worth working around, so length never trips the gate.
            System.setProperty("os.name", "Linux");
            assertThat(NativeImageDriver.needsArgFile(longCmd)).isFalse();
        } finally {
            System.setProperty("os.name", saved);
        }
    }

    @Test
    void pathing_jar_lists_relativized_forward_slash_class_path_entries(@TempDir Path tmp) throws Exception {
        Path outputDir = Files.createDirectories(tmp.resolve("build").resolve("native"));
        Path libs = Files.createDirectories(tmp.resolve("libs"));
        Path a = Files.createFile(libs.resolve("a.jar"));
        Path b = Files.createFile(libs.resolve("b.jar"));

        Path jar = requireNonNull(NativeImageDriver.writePathingJar(outputDir, List.of(a, b)));

        assertThat(jar).isNotNull();
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getManifest().getMainAttributes().getValue("Class-Path"))
                    .isEqualTo("../../libs/a.jar ../../libs/b.jar");
        }
    }

    @Test
    void pathing_jar_is_refused_when_an_entry_contains_a_space(@TempDir Path tmp) throws Exception {
        Path outputDir = Files.createDirectories(tmp.resolve("build").resolve("native"));
        Path spaced =
                Files.createFile(Files.createDirectories(tmp.resolve("my libs")).resolve("a.jar"));

        assertThat(NativeImageDriver.writePathingJar(outputDir, List.of(spaced)))
                .isNull();
        // Refusing must not leave a half-built jar for run()'s finally block to guess at.
        try (Stream<Path> leftovers = Files.list(outputDir)) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    void the_o_argument_drops_the_suffix_native_image_appends_itself() {
        Path bare = Path.of("target/clients/cli/jk");
        Path onDisk = Path.of("target/clients/cli/jk.exe");

        assertThat(NativeImageDriver.imageBasename(bare))
                .isEqualTo(bare.toAbsolutePath().toString());

        if (Os.isWindows()) {
            // The request carries the file that lands on disk; native-image must be given the
            // basename or it writes jk.exe.exe and the presence probe misses it.
            assertThat(NativeImageDriver.imageBasename(onDisk))
                    .isEqualTo(bare.toAbsolutePath().toString())
                    .doesNotEndWith(".exe");
        } else {
            // Nothing is appended off Windows, so a name is passed through exactly as given.
            assertThat(NativeImageDriver.imageBasename(onDisk))
                    .isEqualTo(onDisk.toAbsolutePath().toString());
        }
    }

    @Test
    void the_command_carries_the_basename_not_the_on_disk_name() {
        var req = new NativeImageDriver.Request(
                Path.of("/opt/graalvm"),
                List.of(Path.of("app.jar")),
                "com.example.Main",
                Path.of("target/clients/cli/jk.exe"),
                List.of());
        List<String> cmd = NativeImageDriver.buildCommand(BIN, req);

        assertThat(cmd).containsSequence("-o", NativeImageDriver.imageBasename(Path.of("target/clients/cli/jk.exe")));
    }

    private static @Nullable Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
