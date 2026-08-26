// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Os;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The kotlin plugin's spec decode ({@link CompileSpec#from}) + argument building over the unified
 * JSONL plugin wire — no Build Tools API runtime required.
 */
class CompileSpecTest {

    private static final boolean WINDOWS = Os.isWindows();

    /**
     * Synthetic path root for specs. {@code SpecWriter} serializes {@link Path#toAbsolutePath()};
     * use {@code /tmp} on Unix and {@code %USERPROFILE%\Temp} on Windows (not {@code C:\tmp}).
     */
    private static Path root() {
        return WINDOWS ? Path.of(System.getProperty("user.home"), "Temp") : Path.of("/tmp");
    }

    private static Path path(String first, String... more) {
        return root().resolve(Path.of(first, more));
    }

    private static File file(String first, String... more) {
        return path(first, more).toAbsolutePath().normalize().toFile();
    }

    @Test
    void parses_all_keys_and_repeatables(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of(
                                "classesDir", path("out"), "workdir", path("work"), "snapshotDir", path("snaps")))
                        .configString("jvmTarget", "25")
                        .configString("moduleName", "main")
                        .configString("languageVersion", "2.4")
                        .configString("apiVersion", "2.4")
                        .source(path("src", "A.kt"))
                        .source(path("src", "B.kt"))
                        .cp(path("libs", "stdlib.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(path("libs", "dep.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(path("build", "classes", "other"), PluginProtocol.ROLE_FRIEND)
                        .arg("-no-stdlib")
                        .arg("-Xfoo"));

        assertThat(s.outputDir).isEqualTo(file("out"));
        assertThat(s.workingDir).isEqualTo(file("work"));
        assertThat(s.snapshotDir).isEqualTo(file("snaps"));
        assertThat(s.incremental()).isTrue();
        assertThat(s.jvmTarget).isEqualTo("25");
        assertThat(s.moduleName).isEqualTo("main");
        assertThat(s.languageVersion).isEqualTo("2.4");
        assertThat(s.apiVersion).isEqualTo("2.4");
        assertThat(s.sources).containsExactly(file("src", "A.kt"), file("src", "B.kt"));
        assertThat(s.classpath).containsExactly(file("libs", "stdlib.jar"), file("libs", "dep.jar"));
        assertThat(s.friendPaths).containsExactly(file("build", "classes", "other"));
        assertThat(s.extraArgs).containsExactly("-no-stdlib", "-Xfoo");
    }

    @Test
    void absent_workdir_means_non_incremental(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", path("out")))
                        .configString("jvmTarget", "25")
                        .source(path("src", "A.kt")));
        assertThat(s.incremental()).isFalse();
    }

    @Test
    void value_may_contain_spaces(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", path("with space", "out")))
                        .configString("jvmTarget", "25")
                        .source(path("with space", "A.kt")));
        assertThat(s.outputDir).isEqualTo(file("with space", "out"));
        assertThat(s.sources).containsExactly(file("with space", "A.kt"));
    }

    @Test
    void rejects_missing_required_keys(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                                .configString("jvmTarget", "25")
                                .source(path("a.kt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classesDir");

        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                                .layout(Map.of("classesDir", path("o")))
                                .source(path("a.kt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jvmTarget");

        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                                .layout(Map.of("classesDir", path("o")))
                                .configString("jvmTarget", "25")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void build_args_for_incremental_carry_fir_flag_and_options(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", path("o"), "workdir", path("w")))
                        .configString("jvmTarget", "25")
                        .configString("moduleName", "main")
                        .configString("languageVersion", "2.4")
                        .source(path("a.kt"))
                        .cp(path("libs", "x.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(path("f1"), PluginProtocol.ROLE_FRIEND)
                        .cp(path("f2"), PluginProtocol.ROLE_FRIEND)
                        .arg("-no-stdlib"));

        List<String> args = KotlinCompiler.buildArgs(s);

        assertThat(args).doesNotContain("-d"); // destination is a builder parameter, never a raw arg
        assertThat(args).containsSequence("-jvm-target", "25");
        assertThat(args).containsSequence("-module-name", "main");
        assertThat(args).containsSequence("-language-version", "2.4");
        assertThat(args).containsSequence("-classpath", file("libs", "x.jar").getPath());
        assertThat(args)
                .contains("-Xfriend-paths=" + file("f1").getPath() + ","
                        + file("f2").getPath());
        assertThat(args).contains("-Xuse-fir-ic"); // required by the FIR IC runner
        assertThat(args).endsWith("-no-stdlib"); // free ARGs appended verbatim
    }

    @Test
    void build_args_for_full_compile_omit_fir_flag(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", path("o")))
                        .configString("jvmTarget", "25")
                        .source(path("a.kt")));
        assertThat(KotlinCompiler.buildArgs(s)).doesNotContain("-Xuse-fir-ic");
    }

    private static CompileSpec parse(Path dir, SpecWriter sw) throws IOException {
        Path f = dir.resolve("spec-" + System.nanoTime() + ".spec");
        Files.write(f, sw.lines());
        return CompileSpec.from(PluginSpec.read(f));
    }
}
