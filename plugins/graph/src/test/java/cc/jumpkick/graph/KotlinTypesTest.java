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
 * {@code read} shows the type the compiler gave a Kotlin declaration that writes none, from its
 * compiled class. The class here is a Java stand-in for kotlinc's facade class {@code ApiKt}: the
 * same names, signatures and {@code @Nullable} marks, without a Kotlin compiler in the test.
 */
class KotlinTypesTest {

    private static final String KT = "src/main/kotlin/demo/Api.kt";

    private static final String API = """
            package demo

            fun find(id: Long) = java.util.Optional.of("x")

            fun maybe() = if (true) null else "y"

            fun count() = 3

            fun typed(): Int = 1

            val names = listOf("a")
            """;

    private static final String FACADE = """
            package demo;

            public final class ApiKt {
                public static java.util.Optional<String> find(long id) { return java.util.Optional.of("x"); }
                @org.jetbrains.annotations.Nullable public static String maybe() { return null; }
                public static int count() { return 3; }
                public static int typed() { return 1; }
                public static java.util.List<String> getNames() { return java.util.List.of("a"); }
            }
            """;

    private static final String NULLABLE = """
            package org.jetbrains.annotations;

            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface Nullable {}
            """;

    private static String read(Path dir, String view, Set<String> stale) throws IOException {
        return read(dir, KT, view, stale);
    }

    private static String read(Path tmp, String address, String view, Set<String> stale) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(dir.resolve("jk.toml"), "group = \"demo\"\nname = \"api\"\nversion = \"1.0.0\"\n");
        Path kt = dir.resolve(KT);
        Files.createDirectories(kt.getParent());
        Files.writeString(kt, API);
        Path stand = Files.createDirectories(tmp.resolve("stand-in"));
        Files.writeString(stand.resolve("ApiKt.java"), FACADE);
        Files.writeString(stand.resolve("Nullable.java"), NULLABLE);
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-d",
                                classes.toString(),
                                stand.resolve("ApiKt.java").toString(),
                                stand.resolve("Nullable.java").toString()))
                .isZero();
        FactsIndexing.Ensured e = FactsIndexing.ensure(classes, FactsIndexing.indexPath(dir.resolve("target"), "main"));
        GraphService.Workspace w = new GraphService.Workspace(
                dir,
                dir.resolve("target/graph"),
                dir.resolve("target/jk-cache"),
                List.of(new GraphBuild.ClassIndex(
                        e.index(), "", "main", e.bodyDigest(), e.classes(), stale, List.of())),
                Modules.NONE,
                new ScriptReader(null, null),
                Map.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                ModuleReach.UNKNOWN);
        GraphService.Request r = new GraphService.Request(
                "read",
                List.of(address),
                "bfs",
                3,
                4000,
                List.of(),
                false,
                8,
                null,
                false,
                false,
                null,
                List.of(),
                null,
                Map.of("view", view));
        return GraphService.run(w, r, GraphBuild.Progress.NONE).text();
    }

    @Test
    void a_declaration_with_no_written_type_shows_its_compiled_type(@TempDir Path dir) throws IOException {
        String structure = read(dir, "structure", Set.of());
        assertThat(structure)
                .contains("fun find(id: Long): Optional<String> (inferred)")
                .contains("fun maybe(): String? (inferred)")
                .contains("fun count(): Int (inferred)")
                .contains("fun typed(): Int  ")
                .doesNotContain("fun typed(): Int: ");
    }

    @Test
    void the_headline_view_shows_it_too(@TempDir Path dir) throws IOException {
        assertThat(read(dir, "find", "headline", Set.of())).contains("find: Optional<String> (inferred) [function");
    }

    @Test
    void an_edited_source_claims_no_type(@TempDir Path dir) throws IOException {
        assertThat(read(dir, "structure", Set.of(KT)))
                .contains("fun find(id: Long)")
                .doesNotContain("(inferred)");
    }
}
