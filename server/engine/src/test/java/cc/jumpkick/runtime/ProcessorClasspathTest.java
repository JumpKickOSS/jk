// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [processor-dependencies]} must behave like every other scope in a
 * workspace.
 *
 * <p>The processor classpath merges {@link WorkspaceClasspath} siblings, not the lockfile
 * alone. A workspace sibling is never a Maven artifact, so it is never in the lock: without
 * that merge {@code foo = { workspace = true }} would resolve to nothing.
 */
class ProcessorClasspathTest {

    @Test
    void a_workspace_sibling_processor_reaches_the_processor_classpath(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Path consumer = root.resolve("consumer");
        JkBuild build = JkBuildParser.parse(consumer.resolve("jk.toml"));

        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(consumer, build, Set.of(Scope.PROCESSOR));
        assertThat(siblings.missingSiblingClasses()).isEmpty();

        List<Path> cp = ModuleClasspaths.processorPath(
                consumer,
                build,
                Lockfile.empty("test"),
                new ClasspathResolver(new Cas(tmp.resolve("cas"))),
                siblings,
                Set.of(Scope.PROCESSOR),
                false);

        // The sibling's <module>/target/classes, the tree, not the jar: javac loads the processor and its service
        // registration from it, and it is
        // whole before the sibling packages.
        assertThat(cp).contains(root.resolve("proc/target/classes"));
        assertThat(cp).noneMatch(p -> p.getFileName().toString().endsWith(".jar"));
    }

    @Test
    void an_uncompiled_sibling_processor_is_reported_missing(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        PathUtil.deleteRecursively(root.resolve("proc/target/classes"));
        Path consumer = root.resolve("consumer");

        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(
                consumer, JkBuildParser.parse(consumer.resolve("jk.toml")), Set.of(Scope.PROCESSOR));

        // The build turns this into a hard error rather than silently generating nothing.
        assertThat(siblings.missingSiblingClasses()).hasSize(1);
        assertThat(siblings.missingSiblingClasses().get(0)).contains("proc");
    }

    @Test
    void a_coordinate_processor_absent_from_the_lock_is_reported(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("solo"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "solo"
                version = "1.0.0"

                [processor-dependencies]
                nope = { group = "com.example", name = "nope", version = "1.0.0" }
                """);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));

        assertThat(PlannerSupport.unresolvedProcessorDeps(build, Lockfile.empty("test")))
                .containsExactly("[processor-dependencies] com.example:nope");
    }

    @Test
    void a_workspace_processor_is_not_flagged_as_an_unresolved_coordinate(@TempDir Path tmp) throws Exception {
        Path consumer = workspace(tmp).resolve("consumer");
        JkBuild build = JkBuildParser.parse(consumer.resolve("jk.toml"));
        // parse() rewrites workspace:proc → com.example:proc; WorkspaceClasspath still owns it.
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(consumer, build, Set.of(Scope.PROCESSOR));

        assertThat(PlannerSupport.unresolvedProcessorDeps(build, Lockfile.empty("test"), siblings))
                .isEmpty();
    }

    @Test
    void a_test_processor_sibling_reaches_the_test_processor_path_and_not_the_main_one(@TempDir Path tmp)
            throws Exception {
        Path root = workspace(tmp);
        Path consumer = root.resolve("consumer");
        Files.writeString(consumer.resolve("jk.toml"), """
                group   = "com.example"
                name    = "consumer"
                version = "1.0.0"

                [test-processor-dependencies]
                proc = { workspace = true }
                """);
        JkBuild build = JkBuildParser.parse(consumer.resolve("jk.toml"));
        ClasspathResolver resolver = new ClasspathResolver(new Cas(tmp.resolve("cas")));

        ModuleClasspaths classpaths = derive(consumer, build, Lockfile.empty("test"), resolver);

        assertThat(classpaths.processor()).isEmpty();
        assertThat(classpaths.javacTestProcessor()).contains(root.resolve("proc/target/classes"));
    }

    @Test
    void a_non_inheriting_test_table_is_compile_tests_whole_processor_path(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Path tproc = Files.createDirectories(root.resolve("tproc/target/classes"));
        Files.writeString(root.resolve("tproc/jk.toml"), """
                group   = "com.example"
                name    = "tproc"
                version = "1.0.0"
                """);
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["proc", "tproc", "consumer"]
                """);
        Path consumer = root.resolve("consumer");
        String tables = """
                group   = "com.example"
                name    = "consumer"
                version = "1.0.0"

                [processor-dependencies]
                proc = { workspace = true }

                [test-processor-dependencies]
                """;
        ClasspathResolver resolver = new ClasspathResolver(new Cas(tmp.resolve("cas")));
        Lockfile lock = Lockfile.empty("test");
        Path procClasses = root.resolve("proc/target/classes");

        Files.writeString(consumer.resolve("jk.toml"), tables + "tproc = { workspace = true }\n");
        JkBuild inheriting = JkBuildParser.parse(consumer.resolve("jk.toml"));
        ModuleClasspaths inherited = derive(consumer, inheriting, lock, resolver);
        assertThat(inherited.processor()).containsExactly(procClasses);
        assertThat(inherited.javacTestProcessor())
                .as("by default compile-test runs the shared table's processor beside its own")
                .containsExactly(procClasses, tproc);
        assertThat(inherited.kspTestProcessor()).containsExactly(procClasses, tproc);

        Files.writeString(consumer.resolve("jk.toml"), tables + "inherit = false\ntproc = { workspace = true }\n");
        JkBuild own = JkBuildParser.parse(consumer.resolve("jk.toml"));
        assertThat(derive(consumer, own, lock, resolver).javacTestProcessor())
                .as("inherit = false: the test table alone, the main-only processor stays off the tests")
                .containsExactly(tproc);

        Files.writeString(consumer.resolve("jk.toml"), tables + "inherit = false\n");
        JkBuild none = JkBuildParser.parse(consumer.resolve("jk.toml"));
        assertThat(derive(consumer, none, lock, resolver).javacTestProcessor())
                .as("an empty non-inheriting table declares nothing, so compile-test discovers on its classpath")
                .isEmpty();
    }

    @Test
    void an_unresolved_test_processor_is_named_with_its_table(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("solo"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "solo"
                version = "1.0.0"

                [processor-dependencies]
                nope = { group = "com.example", name = "nope", version = "1.0.0" }

                [test-processor-dependencies]
                test-nope = { group = "com.example", name = "test-nope", version = "1.0.0" }
                """);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));

        assertThat(PlannerSupport.unresolvedProcessorDeps(build, Lockfile.empty("test")))
                .containsExactly(
                        "[processor-dependencies] com.example:nope",
                        "[test-processor-dependencies] com.example:test-nope");
    }

    /** The classpaths of the module at {@code dir}, as the forecast derives them. */
    private static ModuleClasspaths derive(Path dir, JkBuild build, Lockfile lock, ClasspathResolver resolver)
            throws Exception {
        return ModuleClasspaths.derive(
                dir,
                build,
                ModuleShape.of(build, dir, null),
                lock,
                resolver,
                ModuleClasspaths.Contributions.NONE,
                ModuleClasspaths.Mode.FORECAST);
    }

    /** A two-module workspace: {@code consumer} takes {@code proc} as a processor, and proc has compiled. */
    private static Path workspace(Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["proc", "consumer"]
                """);

        Path proc = Files.createDirectories(root.resolve("proc"));
        Files.writeString(proc.resolve("jk.toml"), """
                group   = "com.example"
                name    = "proc"
                version = "1.0.0"
                """);
        Path procClasses = Files.createDirectories(root.resolve("proc/target/classes/META-INF/services"));
        Files.writeString(procClasses.resolve("javax.annotation.processing.Processor"), "com.example.Proc\n");

        Path consumer = Files.createDirectories(root.resolve("consumer"));
        Files.writeString(consumer.resolve("jk.toml"), """
                group   = "com.example"
                name    = "consumer"
                version = "1.0.0"

                [processor-dependencies]
                proc = { workspace = true }
                """);
        return root;
    }
}
