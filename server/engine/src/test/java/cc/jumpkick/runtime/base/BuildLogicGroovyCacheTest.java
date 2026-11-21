// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.RepoGroupBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A build-logic {@code .groovy} script compiles once into the groovy cache tier and runs from there. */
@Tag("integration")
class BuildLogicGroovyCacheTest {

    private static final Supplier<RepoGroup> REPOS = () -> RepoGroupBuilder.buildDefault(JkStores.storeCas());

    /** A script no earlier run of this suite has cached: the sandbox cache outlives a run. */
    private static Path script(Path dir, String name, String body) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, "// " + UUID.randomUUID() + "\n" + body);
        return script;
    }

    @Test
    void the_first_run_caches_the_classes_and_an_unchanged_script_runs_from_them(@TempDir Path dir) throws Exception {
        Path script = script(dir, "before-compile.groovy", """
                def f = outDir.resolve('n.txt').toFile()
                f.text = (f.exists() ? f.text.toInteger() + 1 : 1).toString()
                println "ran in ${projectDir.fileName}"
                """);
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path jar = BuildLogicGroovyHost.compiledJar(script);
        assertThat(jar).doesNotExist();

        String log = BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS);
        assertThat(log).contains("ran in proj");
        assertThat(jar).isNotEmptyFile();
        FileTime compiled = Files.getLastModifiedTime(jar);

        Files.setLastModifiedTime(
                jar,
                FileTime.fromMillis(compiled.toMillis() - Duration.ofDays(40).toMillis()));
        BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS);
        assertThat(out.resolve("n.txt")).hasContent("2");
        assertThat(Files.getLastModifiedTime(jar).toMillis())
                .as("a hit stamps the jar it ran, the age the retention reads")
                .isGreaterThan(compiled.toMillis() - Duration.ofDays(1).toMillis());
    }

    /**
     * The point of the cache: a hit loads the script's classes and never the parser or the bytecode
     * generator. Groovy's runtime touches a few compiler-package types of its own, so those two are
     * what tells a compile apart.
     */
    @Test
    void a_hit_loads_no_compiler_class(@TempDir Path dir) throws Exception {
        Path script = script(dir, "after-compile.groovy", """
                [1, 2, 3].each { outDir.resolve("f${it}.txt").toFile().text = it.toString() }
                """);
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path jar = BuildLogicGroovyHost.compiledJar(script);
        String classpath = BuildLogicGroovyHost.childClasspath(BuildLogicGroovyHost.ensureJars(REPOS));

        String miss = classLoads(classpath, script, jar, project, out);
        assertThat(miss).as("the miss compiles").contains(COMPILER).contains(PARSER);
        assertThat(out.resolve("f3.txt")).hasContent("3");

        Files.delete(out.resolve("f3.txt"));
        String hit = classLoads(classpath, script, jar, project, out);
        assertThat(out.resolve("f3.txt")).hasContent("3");
        assertThat(hit).doesNotContain(COMPILER).doesNotContain(PARSER);
    }

    private static final String COMPILER = "org.codehaus.groovy.classgen.AsmClassGenerator ";
    private static final String PARSER = "org.apache.groovy.parser.antlr4.";

    /** The runner's own run of {@code script}, with every class it loaded in its output. */
    private static String classLoads(String classpath, Path script, Path jar, Path project, Path out) throws Exception {
        String java = JdkFingerprint.java(JavaHomes.runningJavaHome()).toString();
        Processes.Captured run = Processes.capture(
                List.of(
                        java,
                        "-Xlog:class+load=info",
                        "-cp",
                        classpath,
                        GroovyHostJar.MAIN,
                        "run",
                        script.toString(),
                        jar.toString(),
                        project.toString(),
                        out.toString()),
                Duration.ofSeconds(120));
        assertThat(run.exit()).as(run.output()).isZero();
        return run.output();
    }

    @Test
    void an_edited_script_compiles_anew(@TempDir Path dir) throws Exception {
        Path script = script(dir, "before-package.groovy", "outDir.resolve('v.txt').toFile().text = 'one'\n");
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path out = Files.createDirectories(dir.resolve("out"));
        BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS);
        Path first = BuildLogicGroovyHost.compiledJar(script);

        Files.writeString(script, Files.readString(script).replace("'one'", "'two'"));
        BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS);

        assertThat(BuildLogicGroovyHost.compiledJar(script)).isNotEqualTo(first).isNotEmptyFile();
        assertThat(out.resolve("v.txt")).hasContent("two");
    }

    /** A compile error leads with the script's file and line, and leaves nothing in the cache. */
    @Test
    void a_compile_error_names_the_script_and_line_and_caches_nothing(@TempDir Path dir) throws Exception {
        Path script = script(dir, "broken.groovy", "def ok = 1\ndef x = )\nprintln ok\n");
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path out = Files.createDirectories(dir.resolve("out"));

        assertThatThrownBy(() -> BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken.groovy: 3")
                .hasMessageNotContaining("Caught:");
        Path jar = BuildLogicGroovyHost.compiledJar(script);
        assertThat(jar).doesNotExist();
        try (var litter = Files.list(jar.getParent())) {
            assertThat(litter.map(p -> p.getFileName().toString()))
                    .noneMatch(n -> n.startsWith(jar.getFileName().toString()));
        }
    }

    /** A script that throws fails the run with its own output and the exception, as GroovyMain reported it. */
    @Test
    void a_runtime_failure_fails_with_the_scripts_output_and_exception(@TempDir Path dir) throws Exception {
        Path script = script(dir, "after-resources.groovy", """
                println 'before the failure'
                throw new IllegalArgumentException('no such template')
                """);
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path out = Files.createDirectories(dir.resolve("out"));

        assertThatThrownBy(() -> BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before the failure")
                .hasMessageContaining("Caught: java.lang.IllegalArgumentException: no such template");
        assertThat(BuildLogicGroovyHost.compiledJar(script))
                .as("the script compiled; only its run failed")
                .isNotEmptyFile();
    }

    @Test
    void the_ant_and_properties_bindings_reach_the_script(@TempDir Path dir) throws Exception {
        Path script = script(dir, "before-compile-ant.groovy", """
                properties.put('k', 'v')
                ant.mkdir(dir: outDir.resolve('made-by-ant').toString())
                outDir.resolve('p.txt').toFile().text = properties.k
                """);
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path out = Files.createDirectories(dir.resolve("out"));

        BuildLogicGroovyHost.evaluate(script, project, out, () -> false, REPOS);

        assertThat(out.resolve("made-by-ant")).isDirectory();
        assertThat(out.resolve("p.txt")).hasContent("v");
    }
}
