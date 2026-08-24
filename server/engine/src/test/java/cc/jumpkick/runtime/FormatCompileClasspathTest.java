// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classpath {@code optimize-imports} resolves against, and its two obligations: it must reach
 * the worker, and it must be part of {@link FormatKey} so a run cannot skip a file under a stamp
 * written when a different set of types was nameable.
 *
 * <p>Its shape matters as much as its contents. The entry list is derived from the lockfile alone —
 * every module's class output, whether or not it exists yet — so it does not change when a module's
 * tests first compile. If it did, the digest would change with it and re-format the whole tree for
 * output that cannot have differed.
 */
class FormatCompileClasspathTest {

    private static final String LOCK = """
            version = 1
            generated-by = "jk 0.12.0"
            resolution-algorithm = "pubgrub-v1"

            [[module]]
            path    = "."
            group   = "com.acme"
            name    = "root"
            version = "1.0.0"

            [[module]]
            path    = "libs/core"
            group   = "com.acme"
            name    = "core"
            version = "1.0.0"
            """;

    @Test
    void every_module_class_output_is_listed_built_or_not(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        // Only the root's main classes exist; the other three directories have never been built.
        Files.createDirectories(project.resolve("target/classes/main"));

        List<Path> classpath = FormatPlans.compileClasspath(project);

        assertThat(classpath)
                .as("javac ignores a missing entry; dropping it would re-key the tree on first test compile")
                .containsExactly(
                        project.resolve("target/classes/main"),
                        project.resolve("target/classes/test"),
                        project.resolve("target/libs/core/classes/main"),
                        project.resolve("target/libs/core/classes/test"));
    }

    /** No lock, no classpath: {@code jk format} resolves nothing and fetches nothing. */
    @Test
    void a_project_with_no_lockfile_gets_no_classpath(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "[project]\nname = \"root\"\n");

        assertThat(FormatPlans.compileClasspath(tmp)).isEmpty();
    }

    /**
     * A file is stamped clean under the set of types that were nameable when it was formatted.
     * Change that set and the stamp must not survive, or the tree keeps FQCNs that would now
     * shorten.
     */
    @Test
    void the_classpath_is_a_key_input_while_optimize_imports_is_on() {
        String base = key(true, List.of(Path.of("/w/target/classes/main")));

        assertThat(key(true, List.of(Path.of("/w/target/classes/main"), Path.of("/w/target/lib/dep.jar"))))
                .as("a wider classpath can shorten names the last run could not resolve")
                .isNotEqualTo(base);
        assertThat(key(true, List.of())).isNotEqualTo(base);
    }

    /** With the pass off nothing reads the classpath, so changing it must not churn the tree. */
    @Test
    void the_classpath_is_inert_while_optimize_imports_is_off() {
        assertThat(key(false, List.of(Path.of("/w/target/classes/main")))).isEqualTo(key(false, List.of()));
        assertThat(key(false, List.of())).isNotEqualTo(key(true, List.of()));
    }

    /** The worker reads it back through {@code PluginSpec.compileClasspath()} — same role, same paths. */
    @Test
    void the_spec_carries_the_classpath_as_compile_role_entries(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("A.java");
        Files.writeString(source, "class A {}");
        Path classes = tmp.resolve("target/classes/main");
        Path dep = tmp.resolve("dep.jar");

        Path spec = FormatPlans.writeSpec(
                false,
                "palantir",
                "kotlinlang",
                List.of(source),
                List.of(tmp.resolve("palantir.jar")),
                List.of(),
                List.of(),
                List.of(),
                /* optimizeImports */ true,
                true,
                true,
                null,
                List.of(classes, dep),
                tmp.resolve("cache"),
                "cafebabe",
                tmp.resolve("out.spec"));

        assertThat(PluginSpec.read(spec).compileClasspath())
                .containsExactly(classes.toAbsolutePath(), dep.toAbsolutePath());
        assertThat(Files.readString(spec)).contains("\"role\":\"" + PluginProtocol.ROLE_COMPILE + "\"");
    }

    private static String key(boolean optimizeImports, List<Path> compileClasspath) {
        return new FormatKey(
                        "palantir",
                        "2.80.0",
                        "kotlinlang",
                        "0.61",
                        120,
                        optimizeImports,
                        true,
                        true,
                        "1.28.0",
                        null,
                        compileClasspath,
                        null)
                .digest();
    }

    /** A standalone project (no enclosing workspace) with a two-module lock. */
    private static Path project(Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "[project]\nname = \"root\"\n");
        Files.writeString(project.resolve("jk-lock.toml"), LOCK);
        return project;
    }
}
