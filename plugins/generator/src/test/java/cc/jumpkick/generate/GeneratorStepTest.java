// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step body over the shared engine fake and a real fork of {@link StubTool}: the argv the tool
 * receives, the output landing in the declared dir, the located stderr line reaching the
 * diagnostics, and a non-zero exit failing the step with the tool's tail.
 */
class GeneratorStepTest {

    @Test
    void forks_the_tool_jars_main_class_over_the_expanded_inputs(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        Path spec = FakeBuildIo.write(tmp.resolve("api/openapi.yaml"), "openapi: 3.0.0");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));

        GeneratorStep.run(io, entry(null, List.of("api/*.yaml"), List.of("generate", "-i", "${in}", "-o", "${out}")));

        Path out = tmp.resolve("scratch/generated/api");
        assertThat(Files.readAllLines(out.resolve("argv.txt")))
                .containsExactly(
                        "generate",
                        "-i",
                        spec.toString(),
                        "-o",
                        out.toAbsolutePath().toString());
        assertThat(out.resolve("Hello.java")).isRegularFile();
        assertThat(io.labels()).containsExactly("api (1 input)");
        assertThat(io.diagnostics()).containsExactly("warning: " + spec + ":3:1: deprecated `foo`");
    }

    @Test
    void a_closure_dir_is_the_classpath_and_the_named_main_runs(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        Path closure = Files.createDirectories(tmp.resolve("closure"));
        stubJar(closure.resolve("stub-gen-1.0.jar"), null);
        FakeBuildIo.write(closure.resolve("dep-2.0.jar"), "not read");
        io.extra("api", closure);

        GeneratorStep.run(
                io, entry(StubTool.class.getName(), List.of("api/a.yaml"), List.of("-i", "${in}", "-o", "${out}")));

        assertThat(tmp.resolve("scratch/generated/api/Hello.java")).isRegularFile();
    }

    @Test
    void a_failing_tool_fails_the_step_with_its_tail_and_marks_the_finding_an_error(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        Path spec = FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));

        assertThatThrownBy(() -> GeneratorStep.run(
                        io, entry(null, List.of("api/a.yaml"), List.of("-i", "${in}", "-o", "${out}", "--exit", "3"))))
                .hasMessageContaining("failed (exit 3)")
                .hasMessageContaining("generated 1 file");
        assertThat(io.diagnostics()).containsExactly("error: " + spec + ":3:1: deprecated `foo`");
    }

    /**
     * An inherited table whose inputs name nothing under this module — a reactor parent's Avro
     * execution on a module with no schema — is a no-op that says so once, not a failed step: the
     * tool is not forked and the output dir is there, empty, for the compile that reads it.
     */
    @Test
    void no_matching_input_skips_the_tool_with_one_note(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));

        GeneratorStep.run(io, entry(null, List.of("api/*.yaml"), List.of()));

        Path out = tmp.resolve("scratch/generated/api");
        assertThat(out).isDirectory();
        assertThat(out.resolve("argv.txt")).as("the tool was not forked").doesNotExist();
        assertThat(io.labels()).containsExactly("api (no inputs)");
        assertThat(io.diagnostics())
                .containsExactly("warning: [generate.api] inputs [api/*.yaml] match no file under " + io.moduleDir()
                        + "; nothing was generated");
    }

    @Test
    void a_jar_without_a_main_class_asks_for_main(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), null));

        assertThatThrownBy(() -> GeneratorStep.run(io, entry(null, List.of("api/a.yaml"), List.of())))
                .hasMessageContaining("declares no Main-Class")
                .hasMessageContaining("set main");
    }

    @Test
    void an_unpacked_jar_is_a_directory_the_tool_reads(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));
        io.extra("api-unpack", io.jar("protos-1.0.jar", "zipkin.proto"));

        GeneratorEntry entry = new GeneratorEntry(
                "api",
                "api",
                "com.example:stub-gen:1.0",
                null,
                List.of(),
                "io.zipkin.proto3:zipkin-proto3:1.0.0",
                List.of("--proto_path=${unpacked}", "-o", "${out}"),
                GeneratorEntry.Contribution.TEST_SOURCES,
                "generated/api",
                List.of(),
                List.of());
        GeneratorStep.run(io, entry);

        Path unpacked = tmp.resolve("scratch/unpacked");
        assertThat(unpacked.resolve("zipkin.proto")).isRegularFile();
        assertThat(Files.readAllLines(tmp.resolve("scratch/generated/api/argv.txt")))
                .containsExactly(
                        "--proto_path=" + unpacked.toAbsolutePath(),
                        "-o",
                        tmp.resolve("scratch/generated/api").toAbsolutePath().toString());
        assertThat(io.labels()).containsExactly("api (unpacked io.zipkin.proto3:zipkin-proto3:1.0.0)");
    }

    @Test
    void an_entrys_own_classpath_goes_ahead_of_the_tool(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        io.extra("api", io.jar("lib-1.0.jar", "not/a/Main.class"));

        GeneratorEntry entry = new GeneratorEntry(
                "api",
                "api",
                "com.example:lib:1.0",
                StubTool.class.getName(),
                List.of("api/a.yaml"),
                null,
                List.of("-o", "${out}"),
                GeneratorEntry.Contribution.SOURCES,
                "generated/api",
                List.of(stubJar(tmp.resolve("shim/shim.jar"), null)),
                List.of());
        GeneratorStep.run(io, entry);

        assertThat(tmp.resolve("scratch/generated/api/Hello.java")).isRegularFile();
    }

    @Test
    void an_entry_without_a_tool_runs_its_main_from_its_own_classpath(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("src/main/resources/lib/a.jelly"), "<a/>");

        GeneratorEntry entry = new GeneratorEntry(
                "taglib",
                null,
                null,
                StubTool.class.getName(),
                List.of("src/main/resources/**/*.jelly"),
                null,
                List.of("-i", "${in}", "-o", "${out}"),
                GeneratorEntry.Contribution.SOURCES,
                "generated/taglib",
                List.of(stubJar(tmp.resolve("shim/shim.jar"), null)),
                List.of());
        GeneratorStep.run(io, entry);

        assertThat(tmp.resolve("scratch/generated/taglib/Hello.java")).isRegularFile();
        assertThatThrownBy(() -> new GeneratorEntry(
                        "bare",
                        null,
                        null,
                        null,
                        List.of("a.txt"),
                        null,
                        List.of(),
                        GeneratorEntry.Contribution.SOURCES,
                        "generated/bare",
                        List.of(),
                        List.of()))
                .hasMessageContaining("names no tool", List.of());
    }

    @Test
    void discarded_paths_leave_the_output_once_the_tool_ran(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));

        GeneratorEntry entry = new GeneratorEntry(
                "api",
                "api",
                "com.example:stub-gen:1.0",
                null,
                List.of("api/a.yaml"),
                null,
                List.of("-i", "${in}", "-o", "${out}", "--example-dir"),
                GeneratorEntry.Contribution.SOURCES,
                "generated/api",
                List.of(),
                List.of("generated-examples", "**/*.txt"));
        GeneratorStep.run(io, entry);

        Path out = tmp.resolve("scratch/generated/api");
        assertThat(out.resolve("Hello.java")).isRegularFile();
        assertThat(out.resolve("generated-examples")).doesNotExist();
        assertThat(out.resolve("argv.txt")).doesNotExist();
    }

    private static GeneratorEntry entry(@Nullable String main, List<String> inputs, List<String> args) {
        return new GeneratorEntry(
                "api",
                "api",
                "com.example:stub-gen:1.0",
                main,
                inputs,
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/api",
                List.of(),
                List.of());
    }

    /** {@link StubTool}'s class file in a jar, with the given {@code Main-Class} (none when null). */
    static Path stubJar(Path jar, @Nullable String mainClass) throws IOException {
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        String entry = StubTool.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file, manifest);
                InputStream bytes = StubTool.class.getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new JarEntry(entry));
            out.write(bytes.readAllBytes());
            out.closeEntry();
        }
        return jar;
    }
}
