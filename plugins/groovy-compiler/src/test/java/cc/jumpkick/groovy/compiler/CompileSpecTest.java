// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The groovy plugin's spec decode ({@link CompileSpec#from}) over the unified JSONL plugin wire —
 * no Groovy runtime required.
 */
class CompileSpecTest {

    /**
     * Synthetic path root for specs. {@code SpecWriter} serializes {@link Path#toAbsolutePath()};
     * {@code ~/.jk-test-tmp} on every OS — never a drive root.
     */
    private static Path root() {
        return Path.of(System.getProperty("user.home"), ".jk-test-tmp");
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
                        .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                        .layout(Map.of(
                                "classesDir", path("out"),
                                "workdir", path("work")))
                        .extra("stubsOut", path("stubs"))
                        .configString("jvmTarget", "25")
                        .source(path("src", "A.groovy"))
                        .source(path("src", "B.java"))
                        .cp(path("libs", "dep.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(path("libs", "other.jar"), PluginProtocol.ROLE_COMPILE)
                        .arg("--parameters")
                        .arg("--enable-preview"));

        assertThat(s.outputDir).isEqualTo(file("out"));
        assertThat(s.workDir).isEqualTo(file("work"));
        assertThat(s.stubsOut).isEqualTo(file("stubs"));
        assertThat(s.jvmTarget).isEqualTo("25");
        assertThat(s.sources).containsExactly(file("src", "A.groovy"), file("src", "B.java"));
        assertThat(s.classpath).containsExactly(file("libs", "dep.jar"), file("libs", "other.jar"));
        assertThat(s.extraArgs).containsExactly("--parameters", "--enable-preview");
        assertThat(s.joint()).isTrue();
        assertThat(s.javaSources()).containsExactly(file("src", "B.java"));
        assertThat(s.groovySources()).containsExactly(file("src", "A.groovy"));
    }

    @Test
    void absent_optionals_are_null_and_groovy_only_is_not_joint(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                        .layout(Map.of("classesDir", path("out")))
                        .configString("jvmTarget", "25")
                        .source(path("src", "A.groovy")));
        assertThat(s.workDir).isNull();
        assertThat(s.stubsOut).isNull();
        assertThat(s.joint()).isFalse();
    }

    @Test
    void value_may_contain_spaces(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                        .layout(Map.of("classesDir", path("with space", "out")))
                        .configString("jvmTarget", "25")
                        .source(path("with space", "A.groovy")));
        assertThat(s.outputDir).isEqualTo(file("with space", "out"));
        assertThat(s.sources).containsExactly(file("with space", "A.groovy"));
    }

    @Test
    void rejects_missing_required_keys(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                                .configString("jvmTarget", "25")
                                .source(path("a.groovy"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classesDir");

        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                                .layout(Map.of("classesDir", path("o")))
                                .source(path("a.groovy"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jvmTarget");

        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                                .layout(Map.of("classesDir", path("o")))
                                .configString("jvmTarget", "25")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    private static CompileSpec parse(Path dir, SpecWriter sw) throws IOException {
        Path f = dir.resolve("spec-" + System.nanoTime() + ".spec");
        Files.write(f, sw.lines());
        return CompileSpec.from(PluginSpec.read(f));
    }
}
