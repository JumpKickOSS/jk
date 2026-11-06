// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.wire.protocol.GraphAck;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The graph reads a class's compiled facts only while its source is the one the build compiled: a
 * source edited since the build answers from its text, every other class of the module keeps its
 * compiled facts, and the answer names what it skipped. A class file the build did not write
 * leaves the whole module out.
 */
class GraphClassFactsCurrencyTest {

    @Test
    void an_edited_source_drops_its_own_compiled_facts_and_keeps_its_neighbours(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path dir = Files.createDirectories(tmp.resolve("src/main/java/demo"));
        Path app = dir.resolve("App.java");
        Path util = dir.resolve("Util.java");
        Files.writeString(app, "package demo; public class App { void a() { Util.b(); } }\n");
        Files.writeString(util, "package demo; public class Util { static void b() {} }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Path classes = Files.createDirectories(
                BuildLayout.of(tmp, graph.topoOrder().getFirst().manifest()).classesDir());
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), app.toString(), util.toString()))
                .isZero();
        ModuleInputProvenance.recordClasses(tmp, graph, Set.of());
        assertThat(GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false).leftOut())
                .as("a module the run did not build is not vouched for")
                .containsExactly(String.valueOf(tmp.getFileName()));
        // What any successful run records, a test run as much as a build.
        ModuleInputProvenance.recordClasses(
                tmp, graph, Set.of(tmp.toAbsolutePath().normalize()));

        GraphRunner.ClassIndexes built = GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false);
        assertThat(built.rows()).hasSize(1).allSatisfy(row -> assertThat(row).endsWith("\t\t"));
        assertThat(built.leftOut()).isEmpty();
        assertThat(built.edited()).isEmpty();

        Files.writeString(util, "package demo; public class Util { static void b() {} static void c() {} }\n");
        GraphRunner.ClassIndexes edited = GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false);
        assertThat(edited.rows())
                .as("App's classes still hold: only Util's are older than their source")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row).endsWith("\tsrc/main/java/demo/Util.java\t"));
        assertThat(edited.leftOut()).isEmpty();
        assertThat(edited.edited()).containsExactly("src/main/java/demo/Util.java");
        assertThat(GraphRunner.withNote(ack(), edited.leftOut(), edited.edited())
                        .note())
                .contains("1 source edited since the build (src/main/java/demo/Util.java)")
                .contains("every other file keeps its compiled facts");

        Files.write(classes.resolve("demo/App.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        GraphRunner.ClassIndexes foreign = GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false);
        assertThat(foreign.rows())
                .as("a class file the build did not write vouches for nothing")
                .isEmpty();
        assertThat(foreign.leftOut()).containsExactly(String.valueOf(tmp.getFileName()));
        assertThat(GraphRunner.withNote(ack(), foreign.leftOut(), List.of()).note())
                .contains("compiled facts left out");
    }

    @Test
    void a_module_or_test_set_with_sources_and_no_classes_is_named_uncompiled(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        assertThat(GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false).uncompiled())
                .as("no main sources: nothing a compile would make classes of")
                .isEmpty();
        Path dir = Files.createDirectories(tmp.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("App.java"), "package demo; public class App {}\n");
        GraphRunner.ClassIndexes fresh = GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false);
        assertThat(fresh.uncompiled()).containsExactly(String.valueOf(tmp.getFileName()));
        assertThat(fresh.rows()).isEmpty();
        assertThat(fresh.leftOut()).isEmpty();

        Path classes = Files.createDirectories(BuildLayout.moduleTargetDir(tmp).resolve("classes"));
        assertThat(GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false).uncompiled())
                .as("a compile that failed leaves the directory empty")
                .containsExactly(String.valueOf(tmp.getFileName()));
        Files.createDirectories(classes.resolve("demo"));
        Files.write(classes.resolve("demo/App.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        assertThat(GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false).uncompiled())
                .isEmpty();

        Path test = Files.createDirectories(tmp.resolve("src/test/java/demo"));
        Files.writeString(test.resolve("AppTest.java"), "package demo; class AppTest {}\n");
        assertThat(GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false).uncompiled())
                .as("tests that never compiled are named by their directory")
                .containsExactly("src/test");
        Path testClasses =
                Files.createDirectories(BuildLayout.moduleTargetDir(tmp).resolve("test-classes/demo"));
        Files.write(testClasses.resolve("AppTest.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        assertThat(GraphRunner.classIndexes(tmp, tmp.resolve("cache"), false).uncompiled())
                .isEmpty();
        assertThat(GraphRunner.testSet("core", false)).isEqualTo("core/src/test");
        assertThat(GraphRunner.testSet("core", true)).isEqualTo("core/test");
    }

    /**
     * A class set whose compiled facts the cache holds under its compile's key is handed to the graph
     * worker unindexed, with the class count the record keeps; a rename, which reads every index,
     * indexes it all the same.
     */
    @Test
    void a_class_set_the_cache_holds_facts_for_is_not_indexed(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path dir = Files.createDirectories(tmp.resolve("src/main/java/demo"));
        Path app = dir.resolve("App.java");
        Path util = dir.resolve("Util.java");
        Files.writeString(app, "package demo; public class App { void a() { Util.b(); } }\n");
        Files.writeString(util, "package demo; public class Util { static void b() {} }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        BuildLayout layout = BuildLayout.of(tmp, graph.topoOrder().getFirst().manifest());
        Path classes = Files.createDirectories(layout.classesDir());
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), app.toString(), util.toString()))
                .isZero();
        FreshnessStamp.write(
                classes, BuildStamps.JAVA, "compile", "compile-key-1", List.of(app, util), List.of(), 25, "");
        ModuleInputProvenance.recordClasses(
                tmp, graph, Set.of(tmp.toAbsolutePath().normalize()));
        Path cache = tmp.resolve("cache");
        Path index = FactsIndexing.indexPath(layout.buildDir(), "main");
        String key = Objects.requireNonNull(GraphCacheKeys.classes("", "main", List.of("compile-key-1")));

        assertThat(GraphRunner.classIndexes(tmp, cache, false).rows())
                .as("nothing cached: the set is indexed, and its digest is its cache key")
                .containsExactly(index + "\t\tmain\t" + key + "\t2\t\tcompile-key-1");
        Files.delete(index);

        byte[] facts = "{}".getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(facts);
        JkStores.cacheCas(cache).put(facts, sha);
        Path record = Files.createDirectories(GraphCacheKeys.record(cache, key).getParent())
                .resolve(key);
        Files.writeString(
                record,
                ActionRecordText.render(
                        GraphCacheKeys.CLASSES,
                        key,
                        Map.of(GraphCacheKeys.CLASS_COUNT, "2"),
                        Map.of(GraphCacheKeys.OUTPUT, sha),
                        Set.of()));
        assertThat(GraphRunner.classIndexes(tmp, cache, false).rows())
                .containsExactly("\t\tmain\t" + key + "\t2\t\tcompile-key-1");
        assertThat(index).as("a cache hit reads no class file").doesNotExist();

        assertThat(GraphRunner.classIndexes(tmp, cache, true).rows())
                .containsExactly(index + "\t\tmain\t" + key + "\t2\t\tcompile-key-1");
        assertThat(index).exists();
    }

    @Test
    void the_compile_step_says_why_once_and_names_the_modules_without_classes() {
        GraphRunner.Compiled fresh = new GraphRunner.Compiled("a build of this checkout is running", false);
        assertThat(fresh.line())
                .as("there are no last build's classes to answer from")
                .isNull();
        assertThat(fresh.uncompiledLine(List.of("core")))
                .isEqualTo("compile skipped: a build of this checkout is running; core has no compiled classes, so"
                        + " the calls made there are missing: search it for calls too");

        GraphRunner.Compiled mixed = new GraphRunner.Compiled("the code does not compile", true);
        assertThat(mixed.line())
                .isEqualTo("compile skipped: the code does not compile; answering from the last build's classes");
        assertThat(mixed.uncompiledLine(List.of("core", "rest")))
                .isEqualTo("core, rest have no compiled classes, so the calls made there are missing: search them"
                        + " for calls too");

        assertThat(GraphRunner.Compiled.NOTHING.line()).isNull();
        assertThat(GraphRunner.Compiled.NOTHING.uncompiledLine(List.of())).isNull();
    }

    private static GraphAck ack() {
        return new GraphAck(null, "", 0, 0, 0, 0, 0, 0, "", null, List.of());
    }
}
