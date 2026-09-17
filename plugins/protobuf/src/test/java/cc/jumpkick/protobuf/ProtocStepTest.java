// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code protoc} step body over the shared engine fake, with a stub {@code protoc} that
 * records the argv it was handed and writes one generated file where {@code --java_out} points.
 * The assertions are the exact command line: that line is the contract with the real binary.
 */
@DisabledOnOs(OS.WINDOWS)
class ProtocStepTest {

    @Test
    void forks_protoc_over_every_proto_with_java_out_into_gen(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf");
        Path protoDir = tmp.resolve("proto");
        Path a = write(protoDir.resolve("a.proto"), "syntax = \"proto3\";");
        Path b = write(protoDir.resolve("nested/b.proto"), "syntax = \"proto3\";");
        write(protoDir.resolve("README.md"), "not a proto");
        Path argv = tmp.resolve("argv.txt");
        io.extra("protoc", stubProtoc(tmp, argv, 0));

        ProtocStep.run(io);

        Path gen = tmp.resolve("scratch/gen").toAbsolutePath();
        assertThat(Files.readAllLines(argv))
                .as("protoc argv: java_out, the include root, then every .proto in path order")
                .containsExactly(
                        "--java_out=" + gen,
                        "-I",
                        protoDir.toAbsolutePath().toString(),
                        a.toAbsolutePath().toString(),
                        b.toAbsolutePath().toString());
        assertThat(io.labels()).containsExactly("protoc (2 files)");
        assertThat(gen.resolve("Hello.java"))
                .as("the stub's output landed in the gen output")
                .isRegularFile();
    }

    /** {@code lite} prefixes the out dirs; {@code kotlin} adds the DSL generator into the same gen dir. */
    @Test
    void lite_and_kotlin_add_their_generator_flags(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf").config(Map.of("lite", Boolean.TRUE, "kotlin", Boolean.TRUE));
        Path protoDir = tmp.resolve("proto");
        Path only = write(protoDir.resolve("only.proto"), "syntax = \"proto3\";");
        Path argv = tmp.resolve("argv.txt");
        io.extra("protoc", stubProtoc(tmp, argv, 0));

        ProtocStep.run(io);

        Path gen = tmp.resolve("scratch/gen").toAbsolutePath();
        assertThat(Files.readAllLines(argv))
                .containsExactly(
                        "--java_out=lite:" + gen,
                        "-I",
                        protoDir.toAbsolutePath().toString(),
                        "--kotlin_out=lite:" + gen,
                        only.toAbsolutePath().toString());
        assertThat(io.labels()).containsExactly("protoc (1 file)");
    }

    /** {@code [protobuf] src} is where the protos are read from and the include root protoc gets. */
    @Test
    void a_configured_src_dir_is_the_include_root(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf").config("src", "src/main/proto");
        Path protoDir = tmp.resolve("src/main/proto");
        Path only = write(protoDir.resolve("x.proto"), "syntax = \"proto3\";");
        write(tmp.resolve("proto/ignored.proto"), "syntax = \"proto3\";");
        Path argv = tmp.resolve("argv.txt");
        io.extra("protoc", stubProtoc(tmp, argv, 0));

        ProtocStep.run(io);

        List<String> args = Files.readAllLines(argv);
        assertThat(args)
                .contains(
                        "-I",
                        protoDir.toAbsolutePath().toString(),
                        only.toAbsolutePath().toString());
        assertThat(args).noneMatch(s -> s.endsWith("ignored.proto"));
    }

    /** No protos is a no-op: nothing forks, no label, an empty gen. */
    @Test
    void an_empty_or_missing_proto_dir_runs_nothing(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf");
        Path argv = tmp.resolve("argv.txt");
        io.extra("protoc", stubProtoc(tmp, argv, 0));

        ProtocStep.run(io);

        assertThat(argv).doesNotExist();
        assertThat(io.labels()).isEmpty();
        assertThat(tmp.resolve("scratch/gen")).isEmptyDirectory();
    }

    /** The fetched binary arrives read-only and without the executable bit; the step stages and chmods a copy. */
    @Test
    void the_fetched_binary_is_staged_executable_under_scratch(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf");
        write(tmp.resolve("proto/a.proto"), "syntax = \"proto3\";");
        Path fetched = stubProtoc(tmp, tmp.resolve("argv.txt"), 0);
        Files.setPosixFilePermissions(fetched, EnumSet.of(PosixFilePermission.OWNER_READ));
        io.extra("protoc", fetched);

        ProtocStep.run(io);

        Path staged = tmp.resolve("scratch/tools/protoc");
        assertThat(staged).isRegularFile();
        assertThat(Files.isExecutable(staged)).isTrue();
        assertThat(Files.readString(staged)).isEqualTo(Files.readString(fetched));
    }

    /** A non-zero exit is the step's failure, carrying protoc's exit code. */
    @Test
    void a_failing_protoc_fails_the_step_with_its_exit_code(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf");
        write(tmp.resolve("proto/a.proto"), "syntax = \"proto3\";");
        io.extra("protoc", stubProtoc(tmp, tmp.resolve("argv.txt"), 3));

        assertThatThrownBy(() -> ProtocStep.run(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protoc failed (exit 3)");
    }

    /**
     * A {@code [protobuf.<id>]} entry is one protoc plugin: its fetched executable is staged like
     * protoc's and named to protoc as {@code protoc-gen-<id>}, with {@code --<id>_out} into the
     * same gen dir, the entry's {@code options} comma-joined ahead of it.
     */
    @Test
    void a_protoc_plugin_entry_adds_its_plugin_and_out_flags(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf")
                .config(
                        PluginConfig.ENTRIES,
                        Map.of(
                                "grpc-java",
                                Map.of("plugin", "io.grpc:protoc-gen-grpc-java:1.81.0", "options", List.of("a", "b"))));
        Path protoDir = tmp.resolve("proto");
        Path only = write(protoDir.resolve("svc.proto"), "syntax = \"proto3\";");
        Path argv = tmp.resolve("argv.txt");
        io.extra("protoc", stubProtoc(tmp, argv, 0));
        Path fetched = write(tmp.resolve("fetched/protoc-gen-grpc-java-bin"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(fetched, EnumSet.of(PosixFilePermission.OWNER_READ));
        io.extra("protoc-gen-grpc-java", fetched);

        ProtocStep.run(io);

        Path gen = tmp.resolve("scratch/gen").toAbsolutePath();
        Path staged = tmp.resolve("scratch/tools/protoc-gen-grpc-java").toAbsolutePath();
        assertThat(Files.readAllLines(argv))
                .containsExactly(
                        "--java_out=" + gen,
                        "-I",
                        protoDir.toAbsolutePath().toString(),
                        "--plugin=protoc-gen-grpc-java=" + staged,
                        "--grpc-java_out=a,b:" + gen,
                        only.toAbsolutePath().toString());
        assertThat(Files.isExecutable(staged))
                .as("the plugin binary is staged executable")
                .isTrue();
    }

    /** An entry whose fetched executable is absent names the tool the engine should have supplied. */
    @Test
    void a_missing_protoc_plugin_artifact_names_the_step_dependency(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf")
                .config(
                        PluginConfig.ENTRIES,
                        Map.of("grpc-java", Map.of("plugin", "io.grpc:protoc-gen-grpc-java:1.81.0")));
        write(tmp.resolve("proto/a.proto"), "syntax = \"proto3\";");
        io.extra("protoc", stubProtoc(tmp, tmp.resolve("argv.txt"), 0));

        assertThatThrownBy(() -> ProtocStep.run(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-dependency `protoc-gen-grpc-java` was not supplied");
    }

    /**
     * A {@code .proto} a dependency jar carries ({@code google/rpc/status.proto} in
     * proto-google-common-protos, the well-known types in protobuf-java) is importable: each jar
     * holding one is unpacked under scratch and joins the include path after the module's own
     * root, in classpath order; a jar without protos adds no root.
     */
    @Test
    void protos_inside_classpath_jars_are_include_roots(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf");
        Path protoDir = tmp.resolve("proto");
        Path only = write(protoDir.resolve("svc.proto"), "syntax = \"proto3\";");
        io.entry("guava-33.jar", "com.google.guava", "guava", "33", "com/google/common/Guava.class");
        io.entry(
                "proto-google-common-protos-2.17.0.jar",
                "com.google.api.grpc",
                "proto-google-common-protos",
                "2.17.0",
                "google/rpc/status.proto");
        io.entry(
                "protobuf-java-3.25.5.jar",
                "com.google.protobuf",
                "protobuf-java",
                "3.25.5",
                "google/protobuf/any.proto");
        Path argv = tmp.resolve("argv.txt");
        io.extra("protoc", stubProtoc(tmp, argv, 0));

        ProtocStep.run(io);

        Path includes = tmp.resolve("scratch/includes").toAbsolutePath();
        Path common = includes.resolve("proto-google-common-protos-2.17.0");
        Path wellKnown = includes.resolve("protobuf-java-3.25.5");
        assertThat(Files.readAllLines(argv))
                .containsExactly(
                        "--java_out=" + tmp.resolve("scratch/gen").toAbsolutePath(),
                        "-I",
                        protoDir.toAbsolutePath().toString(),
                        "-I",
                        common.toString(),
                        "-I",
                        wellKnown.toString(),
                        only.toAbsolutePath().toString());
        assertThat(common.resolve("google/rpc/status.proto")).isRegularFile();
        assertThat(wellKnown.resolve("google/protobuf/any.proto")).isRegularFile();
        assertThat(includes.resolve("guava-33")).doesNotExist();
    }

    /** The step-dependency the engine fetches must be there; its absence names the manifest table to fix. */
    @Test
    void a_missing_protoc_artifact_names_the_step_dependency(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "protobuf");
        write(tmp.resolve("proto/a.proto"), "syntax = \"proto3\";");

        assertThatThrownBy(() -> ProtocStep.run(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-dependency `protoc` was not supplied");
    }

    /**
     * A stand-in protoc: records its argv one per line into {@code argv}, writes {@code Hello.java}
     * under whatever {@code --java_out} names (with any {@code lite:} prefix stripped), exits with
     * {@code exit}. Written where the engine's fetch would put it — a plain file with no mode bits
     * of its own, which is what the step has to cope with.
     */
    private static Path stubProtoc(Path tmp, Path argv, int exit) throws IOException {
        String script = "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + argv + "'\n"
                + "for a in \"$@\"; do\n"
                + "  case \"$a\" in\n"
                + "    --java_out=*) out=\"${a#--java_out=}\"; out=\"${out#lite:}\"; mkdir -p \"$out\";"
                + " echo 'class Hello {}' > \"$out/Hello.java\";;\n"
                + "  esac\n"
                + "done\n"
                + "exit " + exit + "\n";
        return write(tmp.resolve("fetched/protoc-bin"), script);
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
