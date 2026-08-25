// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
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
 * <p>The processor classpath used to be built from the lockfile alone, while main and test both
 * merged {@link WorkspaceClasspath} siblings. Since a workspace sibling is never a Maven artifact
 * it is never in the lock, so {@code foo = { workspace = true }} resolved to nothing: KSP never
 * ran and the build reported success having generated no code. Found while spiking Knest, whose
 * processors are workspace modules during development.
 */
class ProcessorClasspathTest {

    @Test
    void a_workspace_sibling_processor_reaches_the_processor_classpath(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Path consumer = root.resolve("consumer");
        JkBuild build = JkBuildParser.parse(consumer.resolve("jk.toml"));

        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(consumer, build, Set.of(Scope.PROCESSOR));
        assertThat(siblings.missingSiblingJars()).isEmpty();

        List<Path> cp = PlannerSupport.processorClasspath(
                Lockfile.empty("test"), new ClasspathResolver(new Cas(tmp.resolve("cas"))), siblings);

        // Workspace layout: <ws>/target/<module-rel>/lib/… (not module/target/).
        assertThat(cp).contains(root.resolve("target/proc/lib/proc-1.0.0.jar"));
    }

    @Test
    void an_unbuilt_sibling_processor_is_reported_missing(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Files.delete(root.resolve("target/proc/lib/proc-1.0.0.jar"));
        Path consumer = root.resolve("consumer");

        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(
                consumer, JkBuildParser.parse(consumer.resolve("jk.toml")), Set.of(Scope.PROCESSOR));

        // The build turns this into a hard error rather than silently generating nothing.
        assertThat(siblings.missingSiblingJars()).hasSize(1);
        assertThat(siblings.missingSiblingJars().get(0)).contains("proc");
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
                .containsExactly("com.example:nope");
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

    /** A two-module workspace: {@code consumer} takes {@code proc} as a processor, and proc is built. */
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
        Path procJar = root.resolve("target/proc/lib/proc-1.0.0.jar");
        Files.createDirectories(procJar.getParent());
        Files.writeString(procJar, "not-really-a-jar");

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
