// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;

/**
 * {@code optimize-imports} shortens a fully-qualified name only when OpenRewrite can attribute it
 * to a type, and it can only do that against a classpath. Built without one the pass parsed every
 * file, resolved every name outside {@code java.*} to {@code Unknown}, changed nothing and reported
 * success — which is how a tree-wide house rule ("no FQCN except collisions") came to be enforced
 * by a no-op.
 *
 * <p>So these compile a real class and assert against its real class output: no classpath, no
 * shortening; classpath, shortening.
 */
class CodeFormatterRewriteTest {

    private static final String LIBRARY = """
            package cc.jumpkick.foo;

            public final class Bar {
                public static String hi() {
                    return "hi";
                }
            }
            """;

    private static final String CALLER = """
            package demo;

            public class Uses {
                public String go() {
                    return cc.jumpkick.foo.Bar.hi();
                }
            }
            """;

    @Test
    void an_fqcn_in_a_method_body_is_shortened_only_with_the_compile_classpath(@TempDir Path tmp) throws Exception {
        Path classes = compileLibrary(tmp);

        Path withoutCp = caller(tmp.resolve("nocp"));
        CodeFormatter.Rewrite withoutCpOutcome = CodeFormatter.applyRewrite(
                new ShortenFullyQualifiedTypeReferences(), withoutCp.toFile(), true, List.of());

        assertThat(withoutCpOutcome)
                .as("with no classpath the type cannot be resolved, so there is nothing to shorten"
                        + " — and the file parsed, so this is UNCHANGED, not UNPARSEABLE")
                .isEqualTo(CodeFormatter.Rewrite.UNCHANGED);
        assertThat(Files.readString(withoutCp))
                .contains("cc.jumpkick.foo.Bar.hi()")
                .doesNotContain("import ");

        Path withCp = caller(tmp.resolve("cp"));
        CodeFormatter.Rewrite withCpOutcome = CodeFormatter.applyRewrite(
                new ShortenFullyQualifiedTypeReferences(), withCp.toFile(), true, List.of(classes));

        assertThat(withCpOutcome).isEqualTo(CodeFormatter.Rewrite.CHANGED);
        assertThat(Files.readString(withCp))
                .as("the Done criterion: cc.jumpkick.foo.Bar in a method body becomes Bar + an import")
                .contains("import cc.jumpkick.foo.Bar;")
                .contains("return Bar.hi();")
                .doesNotContain("cc.jumpkick.foo.Bar.hi()");
    }

    /** {@code --check} must not write, whatever the recipe found. */
    @Test
    void check_mode_reports_the_change_without_writing_it(@TempDir Path tmp) throws Exception {
        Path classes = compileLibrary(tmp);
        Path caller = caller(tmp.resolve("check"));
        String before = Files.readString(caller);

        CodeFormatter.Rewrite outcome = CodeFormatter.applyRewrite(
                new ShortenFullyQualifiedTypeReferences(), caller.toFile(), false, List.of(classes));

        assertThat(outcome).isEqualTo(CodeFormatter.Rewrite.CHANGED);
        assertThat(Files.readString(caller)).isEqualTo(before);
    }

    /**
     * The wire half: the host sends the classpath as ordinary {@code cp} lines with the compile
     * role, and {@link CodeFormatter.Spec} reads it back through the SDK accessor every other
     * worker uses. A rename on either side breaks this, not the shortening silently.
     */
    @Test
    void the_spec_carries_the_compile_classpath_to_the_worker(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("dep.jar");
        Path classes = tmp.resolve("classes");
        Path spec = tmp.resolve("fmt.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "format", "jk-formatter")
                        .configBool("optimizeImports", true)
                        .classpath(List.of(classes, jar), PluginProtocol.ROLE_COMPILE)
                        .lines(),
                StandardCharsets.UTF_8);

        CodeFormatter.Spec parsed = CodeFormatter.Spec.from(PluginSpec.read(spec));

        assertThat(parsed.optimizeImports).isTrue();
        assertThat(parsed.compileClasspath).containsExactly(classes.toAbsolutePath(), jar.toAbsolutePath());
    }

    /** Compile {@link #LIBRARY} to {@code <tmp>/classes} and return that directory. */
    private static Path compileLibrary(Path tmp) throws IOException {
        Path src = tmp.resolve("lib/cc/jumpkick/foo/Bar.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, LIBRARY);
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("the test JVM must be a JDK").isNotNull();
        int rc = javac.run(null, null, null, "-d", classes.toString(), src.toString());
        assertThat(rc).as("javac exit for the probe library").isZero();
        return classes;
    }

    private static Path caller(Path dir) throws IOException {
        Path file = dir.resolve("demo/Uses.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CALLER);
        return file;
    }
}
