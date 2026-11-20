// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.KeyProbe;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    /** A key probe names what the tool reads instead (a live schema): inputs that match nothing still run it. */
    @Test
    void an_entry_with_a_key_probe_runs_the_tool_without_matching_inputs(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));
        GeneratorEntry probed = new GeneratorEntry(
                "api",
                "api",
                "com.example:stub-gen:1.0",
                null,
                List.of("api/*.yaml"),
                null,
                List.of("-o", "${out}"),
                GeneratorEntry.Contribution.SOURCES,
                "generated/api",
                List.of(),
                List.of(),
                new KeyProbe("com.example.Digest", List.of("api"), List.of("--url", "jdbc:x")));

        GeneratorStep.run(io, probed);

        assertThat(tmp.resolve("scratch/generated/api/argv.txt"))
                .as("the tool was forked")
                .isRegularFile();
        assertThat(io.labels()).containsExactly("api (live input)");
        assertThat(probed.task().keyProbe()).isEqualTo(probed.keyProbe());
    }

    /** An entry's {@code env} reaches the forked tool's environment and nothing of it its command line. */
    @Test
    void the_entrys_env_reaches_the_tool_off_its_command_line(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));
        GeneratorEntry entry = GeneratorEntry.fromConfig(
                "api",
                Map.of(
                        "tool", "com.example:stub-gen:1.0",
                        "inputs", List.of("api/a.yaml"),
                        "args", List.of("-i", "${in}", "-o", "${out}", "--env", "DB_PASSWORD"),
                        "env", Map.of("DB_PASSWORD", "s3cret")));

        GeneratorStep.run(io, entry);

        Path out = tmp.resolve("scratch/generated/api");
        assertThat(out.resolve("env.txt")).hasContent("s3cret");
        assertThat(Files.readString(out.resolve("argv.txt"))).doesNotContain("s3cret");
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
                List.of(),
                null);
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
                List.of(),
                null);
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
                List.of(),
                null);
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
                        List.of(),
                        null))
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
                List.of("generated-examples", "**/*.txt"),
                null);
        GeneratorStep.run(io, entry);

        Path out = tmp.resolve("scratch/generated/api");
        assertThat(out.resolve("Hello.java")).isRegularFile();
        assertThat(out.resolve("generated-examples")).doesNotExist();
        assertThat(out.resolve("argv.txt")).doesNotExist();
    }

    @Test
    void a_discard_path_outside_the_output_or_naming_it_is_refused_when_the_table_is_read(@TempDir Path tmp) {
        for (String path : List.of("../../src", tmp.resolve("src").toString(), ".", "", "a/../..")) {
            assertThatThrownBy(() -> GeneratorEntry.fromConfig(
                            "api",
                            Map.of(
                                    "tool", "com.example:stub-gen:1.0",
                                    "inputs", List.of("api/a.yaml"),
                                    "discard", List.of(path))))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("[generate.api] discard \"" + path + "\" is not under the output directory");
        }
        assertThat(GeneratorEntry.fromConfig(
                                "api",
                                Map.of(
                                        "tool", "com.example:stub-gen:1.0",
                                        "inputs", List.of("api/a.yaml"),
                                        "discard", List.of("generated-examples", "a/../b", "../**/*.txt")))
                        .discard())
                .hasSize(3);
    }

    @Test
    void an_out_outside_the_steps_output_root_or_naming_it_is_refused_when_the_table_is_read(@TempDir Path tmp) {
        for (String out : List.of("../../..", tmp.toString(), ".", "", "generated/../..")) {
            assertThatThrownBy(() -> GeneratorEntry.fromConfig(
                            "api",
                            Map.of("tool", "com.example:stub-gen:1.0", "inputs", List.of("api/a.yaml"), "out", out)))
                    .as(out)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("[generate.api] out \"" + out + "\" is not under the step's output root");
        }
    }

    @Test
    void an_entry_name_that_is_not_a_plain_name_is_refused_when_the_table_is_read() {
        for (String name : List.of("x/../../..", "..", ".", "", "a b", "a\\b")) {
            assertThatThrownBy(() -> GeneratorEntry.fromConfig(
                            name, Map.of("tool", "com.example:stub-gen:1.0", "inputs", List.of("api/a.yaml"))))
                    .as(name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("[generate." + name + "] is not a plain name; use letters, digits, '.', '_' or '-'");
        }
        assertThat(GeneratorEntry.fromConfig(
                                "api_v2.1-x",
                                Map.of("tool", "com.example:stub-gen:1.0", "inputs", List.of("api/a.yaml")))
                        .stepName())
                .isEqualTo("generate-api_v2.1-x");
    }

    @Test
    void an_out_outside_the_steps_output_root_never_runs_the_tool_or_discards_outside_it(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "generate");
        FakeBuildIo.write(tmp.resolve("api/a.yaml"), "a");
        Path src = FakeBuildIo.write(tmp.resolve("src/Keep.java"), "class Keep {}");
        io.extra("api", stubJar(tmp.resolve("tools/stub-gen-1.0.jar"), StubTool.class.getName()));

        for (String out : List.of("..", tmp.toString())) {
            assertThatThrownBy(() -> GeneratorStep.run(
                            io,
                            new GeneratorEntry(
                                    "api",
                                    "api",
                                    "com.example:stub-gen:1.0",
                                    null,
                                    List.of("api/a.yaml"),
                                    null,
                                    List.of("-i", "${in}", "-o", "${out}"),
                                    GeneratorEntry.Contribution.SOURCES,
                                    out,
                                    List.of(),
                                    List.of("src"),
                                    null)))
                    .as(out)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(src).as(out).hasContent("class Keep {}");
            assertThat(tmp.resolve("argv.txt")).as(out).doesNotExist();
        }
    }

    @Test
    void discard_refuses_a_path_outside_the_output_or_the_output_itself_before_deleting_anything(@TempDir Path tmp)
            throws Exception {
        Path src = FakeBuildIo.write(tmp.resolve("src/Keep.java"), "class Keep {}");
        Path out = Files.createDirectories(tmp.resolve("scratch/generated/api"));
        Path generated = FakeBuildIo.write(out.resolve("Hello.java"), "class Hello {}");
        Path examples = FakeBuildIo.write(out.resolve("generated-examples/Example.java"), "class Example {}");

        for (String path : List.of("../../../src", tmp.resolve("src").toString(), ".", "")) {
            assertThatThrownBy(() -> GeneratorStep.discard(out, List.of("generated-examples", path)))
                    .as(path)
                    .isInstanceOf(IOException.class)
                    .hasMessage("discard \"" + path + "\" is not under the output directory");
        }

        assertThat(src).hasContent("class Keep {}");
        assertThat(generated).isRegularFile();
        assertThat(examples).isRegularFile();
    }

    @Test
    void discard_never_deletes_through_a_link_out_of_the_output(@TempDir Path tmp) throws Exception {
        Path src = FakeBuildIo.write(tmp.resolve("src/Keep.java"), "class Keep {}");
        Path out = Files.createDirectories(tmp.resolve("scratch/generated/api"));
        Path link = Symlinks.create(out.resolve("link"), tmp.resolve("src"));

        assertThatThrownBy(() -> GeneratorStep.discard(out, List.of("link/Keep.java")))
                .isInstanceOf(IOException.class)
                .hasMessage("discard \"link/Keep.java\" leads out of the output directory through a link");
        assertThat(src).hasContent("class Keep {}");

        GeneratorStep.discard(out, List.of("link", "**/*.java"));
        assertThat(link).doesNotExist();
        assertThat(src).hasContent("class Keep {}");
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
                List.of(),
                null);
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

    @Test
    void a_failure_leads_with_the_tools_own_error_not_its_log() {
        assertThat(
                        GeneratorStep.failureCause(
                                List.of(
                                        "[main] INFO  o.o.codegen.DefaultGenerator - Generating with dryRun=false",
                                        "Exception in thread \"main\" java.lang.IllegalArgumentException: no such annotation library",
                                        "\tat org.openapitools.codegen.languages.AbstractJavaCodegen.processOpts(AbstractJavaCodegen.java:442)")))
                .isEqualTo("java.lang.IllegalArgumentException: no such annotation library");
        assertThat(GeneratorStep.failureCause(List.of(
                        "Exception in thread \"main\" java.lang.RuntimeException: wrapped",
                        "\tat a.B.c(B.java:1)",
                        "Caused by: java.io.FileNotFoundException: spec.yml")))
                .isEqualTo("java.io.FileNotFoundException: spec.yml");
        assertThat(GeneratorStep.failureCause(List.of("[main] INFO starting", "[main] ERROR the spec has 2 errors")))
                .isEqualTo("the spec has 2 errors");
        assertThat(GeneratorStep.failureCause(List.of("[main] INFO starting"))).isNull();
    }

    /** What the tool also reads is keyed with the inputs' bases, and never reaches its command line. */
    @Test
    void reads_join_the_key_but_not_the_arguments() {
        GeneratorEntry entry = new GeneratorEntry(
                "api",
                "api",
                "com.example:stub-gen:1.0",
                null,
                List.of("api/openapi.yaml"),
                null,
                List.of("-o", "${out}", "${inputs}"),
                GeneratorEntry.Contribution.SOURCES,
                "generated/api",
                List.of(),
                List.of(),
                null,
                List.of("api"));

        assertThat(entry.inputBases()).containsExactly("api/openapi.yaml", "api");
        assertThat(entry.inputs()).containsExactly("api/openapi.yaml");
    }
}
