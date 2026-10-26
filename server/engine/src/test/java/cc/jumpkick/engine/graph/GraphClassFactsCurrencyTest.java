// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.wire.protocol.GraphAck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        ModuleInputProvenance.record(
                tmp, graph, PreflightMemo.snapshotFingerprints(graph, false).fingerprints());

        GraphRunner.ClassIndexes built = GraphRunner.classIndexes(tmp);
        assertThat(built.rows()).hasSize(1).allSatisfy(row -> assertThat(row).endsWith("\t"));
        assertThat(built.leftOut()).isEmpty();
        assertThat(built.edited()).isEmpty();

        Files.writeString(util, "package demo; public class Util { static void b() {} static void c() {} }\n");
        GraphRunner.ClassIndexes edited = GraphRunner.classIndexes(tmp);
        assertThat(edited.rows())
                .as("App's classes still hold: only Util's are older than their source")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row).endsWith("\tsrc/main/java/demo/Util.java"));
        assertThat(edited.leftOut()).isEmpty();
        assertThat(edited.edited()).containsExactly("src/main/java/demo/Util.java");
        assertThat(GraphRunner.withNote(ack(), edited.leftOut(), edited.edited())
                        .note())
                .contains("1 source edited since the build (src/main/java/demo/Util.java)")
                .contains("every other file keeps its compiled facts");

        Files.write(classes.resolve("demo/App.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        GraphRunner.ClassIndexes foreign = GraphRunner.classIndexes(tmp);
        assertThat(foreign.rows())
                .as("a class file the build did not write vouches for nothing")
                .isEmpty();
        assertThat(foreign.leftOut()).containsExactly(String.valueOf(tmp.getFileName()));
        assertThat(GraphRunner.withNote(ack(), foreign.leftOut(), List.of()).note())
                .contains("compiled facts left out");
    }

    private static GraphAck ack() {
        return new GraphAck(null, "", 0, 0, 0, 0, 0, 0, 0, "", null);
    }
}
