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
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The graph reads a module's compiled facts only while its classes are the ones its sources
 * compile to; a source edited since the build leaves them out, and the answer says so.
 */
class GraphClassFactsCurrencyTest {

    @Test
    void a_module_edited_since_its_build_contributes_no_compiled_facts(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path src = Files.createDirectories(tmp.resolve("src/main/java/demo")).resolve("App.java");
        Files.writeString(src, "package demo; public class App { void a() { b(); } void b() {} }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Path classes = Files.createDirectories(
                BuildLayout.of(tmp, graph.topoOrder().getFirst().manifest()).classesDir());
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString()))
                .isZero();
        ModuleInputProvenance.record(
                tmp, graph, PreflightMemo.snapshotFingerprints(graph, false).fingerprints());

        GraphRunner.ClassIndexes built = GraphRunner.classIndexes(tmp);
        assertThat(built.rows()).hasSize(1);
        assertThat(built.leftOut()).isEmpty();

        Files.writeString(src, "package demo; public class App { void a() {} void b() {} }\n");
        GraphRunner.ClassIndexes edited = GraphRunner.classIndexes(tmp);
        assertThat(edited.rows()).as("the classes still say a() calls b()").isEmpty();
        assertThat(edited.leftOut()).containsExactly(String.valueOf(tmp.getFileName()));
        assertThat(GraphRunner.withNote(new GraphAck(null, "", 0, 0, 0, 0, 0, 0, 0, "", null), edited.leftOut())
                        .note())
                .contains("compiled facts left out");
    }
}
