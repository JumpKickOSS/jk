// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compiled classes and the source scan give one nested type one id, and two nested types two. */
class NestedTypeIdsTest {

    private static final String DIR = "src/main/java/a/";

    private static final Map<String, String> SOURCES = Map.of("Models.java", """
            package a;

            class A {
                static class Builder {}

                static Builder builder() {
                    return new Builder();
                }
            }

            class B {
                static class Builder {}
            }
            """, "Outer.java", """
            package a;

            public class Outer {
                static class Inner {
                    static class Leaf {}
                }
            }
            """);

    @Test
    void same_named_nested_types_in_one_file_get_distinct_ids_from_both_extractors(@TempDir Path dir)
            throws IOException {
        Set<String> compiled = types(compiled(dir));
        Set<String> scanned = new TreeSet<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            String rel = DIR + e.getKey();
            scanned.addAll(types(Objects.requireNonNull(Extractors.forPath(rel)).extract(rel, e.getValue())));
        }
        assertThat(compiled)
                .containsExactlyInAnyOrder(
                        "src_main_java_a_models_a",
                        "src_main_java_a_models_a_0_builder",
                        "src_main_java_a_models_b",
                        "src_main_java_a_models_b_0_builder",
                        "src_main_java_a_outer_outer",
                        "src_main_java_a_outer_inner",
                        "src_main_java_a_outer_inner_0_leaf");
        assertThat(scanned).isEqualTo(compiled);
    }

    @Test
    void a_nested_type_and_its_owners_same_named_method_are_two_nodes(@TempDir Path dir) throws IOException {
        Fragment f = compiled(dir);
        assertThat(f.nodes())
                .extracting(GraphNode::id)
                .contains("src_main_java_a_models_a_builder", "src_main_java_a_models_a_0_builder");
    }

    private static Set<String> types(Fragment f) {
        Set<String> out = new TreeSet<>();
        for (GraphNode n : f.nodes()) {
            if ("class".equals(Decl.kind(n))) out.add(n.id());
        }
        return out;
    }

    private static Fragment compiled(Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve(DIR));
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .isZero();
        Path index = FactsIndexing.indexPath(dir.resolve("target"), "main");
        FactsIndexing.ensure(classes, index);
        return JvmFacts.map(FactsFormat.read(index), (pkg, file) -> "src/main/java/" + pkg + "/" + file);
    }
}
