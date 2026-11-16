// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dynamic Groovy calls a method by name at run time, so its classes count none of its calls: a
 * rename lists those uses for review and edits none of them; {@code @CompileStatic} Groovy is not
 * listed by this rule.
 */
class GroovyRenameTest {

    private static final String STORE = """
            package demo;

            public class Store {
                public void put(String item) {}
            }
            """;

    private static final String DYN = """
            package demo

            class Dyn {
                void fill(Store store) {
                    store.put("a")
                }
            }
            """;

    private static String rename(Path dir, String groovy) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), "group = \"demo\"\nname = \"dyn\"\nversion = \"1.0.0\"\n");
        Path java = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(java.resolve("Store.java"), STORE);
        Files.writeString(
                Files.createDirectories(dir.resolve("src/main/groovy/demo")).resolve("Dyn.groovy"), groovy);
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-g",
                                "-d",
                                classes.toString(),
                                java.resolve("Store.java").toString()))
                .isZero();
        FactsIndexing.Ensured e = FactsIndexing.ensure(classes, FactsIndexing.indexPath(dir.resolve("target"), "main"));
        GraphService.Workspace w = new GraphService.Workspace(
                dir,
                dir.resolve("target/graph"),
                dir.resolve("target/jk-cache"),
                List.of(new GraphBuild.ClassIndex(
                        e.index(), "", "main", e.bodyDigest(), e.classes(), Set.of(), List.of())),
                Modules.NONE,
                new ScriptReader(null, null),
                Map.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                ModuleReach.UNKNOWN);
        GraphService.Request r = new GraphService.Request(
                "rename", List.of("Store.put", "add"), "bfs", 3, 4000, List.of(), false, 8, null, false, false);
        return GraphService.run(w, r, GraphBuild.Progress.NONE).text();
    }

    @Test
    void a_dynamic_groovy_call_is_listed_for_review_and_not_edited(@TempDir Path dir) throws IOException {
        String answer = rename(dir, DYN);
        assertThat(answer)
                .contains("Renamed Store.put to add")
                .contains("dynamic Groovy, bound by name at run time")
                .contains("src/main/groovy/demo/Dyn.groovy", "store.put(\"a\")")
                .doesNotContain("The task is done");
        assertThat(Files.readString(dir.resolve("src/main/groovy/demo/Dyn.groovy")))
                .contains("store.put(\"a\")");
        assertThat(Files.readString(dir.resolve("src/main/java/demo/Store.java")))
                .contains("void add(");
    }

    @Test
    void a_compile_static_groovy_source_is_not_listed_by_the_dynamic_rule(@TempDir Path dir) throws IOException {
        String typed = DYN.replace("class Dyn", "@groovy.transform.CompileStatic\nclass Dyn");
        assertThat(rename(dir, typed)).doesNotContain("Dyn.groovy");
    }
}
