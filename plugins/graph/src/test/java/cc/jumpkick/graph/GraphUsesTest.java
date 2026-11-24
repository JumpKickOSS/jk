// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code callers} on a field, a type, an annotation or a constructor: every use at its line, from
 * the reference index for Java and by name, marked inferred, for a source the index does not
 * describe.
 */
class GraphUsesTest {

    private static final String PKG = "src/main/java/com/acme/shop/";

    private static final String BOX = """
            package com.acme.shop;

            public class Box {
                int size;
                int unused;

                public Box() {}

                public Box(int s) {
                    this.size = s;
                }

                static final int LIMIT = 4;
            }
            """;

    private static final String MARKER = """
            package com.acme.shop;

            public @interface Marker {}
            """;

    private static final String USE = """
            package com.acme.shop;

            @Marker
            public class Use {
                int twice(Box b) {
                    b.size = 2;
                    b.size += 1;
                    return b.size + b.size;
                }

                Box make() {
                    return new Box(3);
                }

                static final int MAX = Box.LIMIT;
            }
            """;

    private static final String BOX_T = "T com.acme.shop.Box";
    private static final String SIZE = "F com.acme.shop.Box#size";
    private static final String UNUSED = "F com.acme.shop.Box#unused";
    private static final String MARKER_T = "T com.acme.shop.Marker";
    private static final String LIMIT = "F com.acme.shop.Box#LIMIT";

    private static Path workspace(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"
                java = 25
                """);
        Path pkg = Files.createDirectories(dir.resolve(PKG));
        Map<String, String> sources = Map.of("Box.java", BOX, "Marker.java", MARKER, "Use.java", USE);
        List<String> args = new ArrayList<>(
                List.of("-g", "-d", dir.resolve("target/classes").toString()));
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = pkg.resolve(e.getKey());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        Files.createDirectories(dir.resolve("target/classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .isZero();
        index(
                dir,
                "Box.java",
                "d " + BOX_T + " class Box",
                "d " + SIZE + " int size",
                "d " + UNUSED + " int unused",
                "w " + SIZE + " this.size",
                "d " + LIMIT + " int LIMIT");
        index(dir, "Marker.java", "d " + MARKER_T + " @interface Marker");
        index(
                dir,
                "Use.java",
                "r " + MARKER_T + " @Marker",
                "r " + BOX_T + " twice(Box",
                "w " + SIZE + " b.size = 2",
                "w " + SIZE + " b.size += 1",
                "r " + SIZE + " return b.size",
                "r " + SIZE + " + b.size;",
                "r " + BOX_T + "     Box make",
                "r " + BOX_T + " new Box",
                "r " + BOX_T + " = Box.LIMIT",
                "r " + LIMIT + " Box.LIMIT");
        return dir;
    }

    /**
     * The index file the compile writes for {@code file}: each entry is {@code <kind> <symbol kind>
     * <symbol> <needle>}, the span at the needle's last occurrence of the symbol's simple name.
     */
    private static void index(Path root, String file, String... entries) throws IOException {
        String text = Files.readString(root.resolve(PKG + file));
        record At(char kind, String symbol, int start) {}
        List<At> spans = new ArrayList<>();
        for (String entry : entries) {
            String[] f = entry.split(" ", 4);
            String symbol = f[1] + " " + f[2];
            String needle = f[3];
            String name = ReferenceIndex.simpleName(symbol);
            int at = text.indexOf(needle);
            assertThat(at).as(needle).isNotNegative();
            spans.add(new At(f[0].charAt(0), symbol, at + needle.lastIndexOf(name)));
        }
        spans.sort(Comparator.comparingInt(At::start));
        StringBuilder out = new StringBuilder(
                ReferenceIndex.HEADER + Hashing.sha256Hex(text.getBytes(StandardCharsets.UTF_8)) + "\n");
        Map<String, Integer> ids = new HashMap<>();
        int previous = 0;
        for (At a : spans) {
            Integer id = ids.get(a.symbol());
            if (id == null) {
                id = ids.size();
                ids.put(a.symbol(), id);
                out.append('=').append(a.symbol()).append('\n');
            }
            out.append(a.kind())
                    .append(id)
                    .append('\t')
                    .append(a.start() - previous)
                    .append('\n');
            previous = a.start();
        }
        Path f = root.resolve("target/incremental/classes-refs/" + PKG + file + ".refs");
        Files.createDirectories(f.getParent());
        Files.writeString(f, out);
    }

    private static String callers(Path root, String address) throws IOException {
        return callers(root, address, Set.of());
    }

    private static String callers(Path root, String address, Set<String> stale) throws IOException {
        return callers(root, address, stale, null);
    }

    private static String callers(Path root, String address, Set<String> stale, @Nullable String scope)
            throws IOException {
        Path classes = root.resolve("target/classes");
        FactsIndexing.Ensured e =
                FactsIndexing.ensure(classes, FactsIndexing.indexPath(root.resolve("target"), "main"));
        GraphService.Workspace w = new GraphService.Workspace(
                root,
                root.resolve("target/graph"),
                root.resolve("target/jk-cache"),
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
                "usages",
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
                scope,
                List.of(),
                null,
                Map.of());
        return GraphService.run(w, r, GraphBuild.Progress.NONE).text();
    }

    /** Paths that match no file would make every answer "nothing uses it": they are refused instead. */
    @Test
    void aScopeThatMatchesNoFileIsRefusedNotAnsweredEmpty(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThatThrownBy(() -> callers(root, "Box.size", Set.of(), "main"))
                .hasMessage("scope 'main' matches no source file; give paths from the workspace root"
                        + " (source_set picks main or test)");
        assertThat(callers(root, "Box.size", Set.of(), "src/main/java"))
                .startsWith("Uses of Box.size (field): 5 in main sources");
    }

    @Test
    void aFieldsUsesAreEveryReadAndWriteAtItsLineAndComplete(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        String answer = callers(root, "Box.size");
        assertThat(answer)
                .startsWith("Uses of Box.size (field): 5 in main sources, 0 in tests (3 writes, 2 reads).")
                .contains("Complete: javac resolved each Java use at its line")
                .contains(String.join(
                        "\n",
                        "  writes:",
                        "    Box.Box()  src/main/java/com/acme/shop/Box.java:10",
                        "        L10| this.size = s;",
                        "    Use.twice()  src/main/java/com/acme/shop/Use.java:6,7",
                        "        L6| b.size = 2;",
                        "        L7| b.size += 1;",
                        "  reads:",
                        "    Use.twice()  src/main/java/com/acme/shop/Use.java:8",
                        "        L8| return b.size + b.size;"));
        assertThat(callers(root, "Box#size"))
                .as("Type#field, as javadoc writes it")
                .isEqualTo(answer);
    }

    @Test
    void anUnusedFieldHasNoUsesUnderTheSameCompletenessRule(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThat(callers(root, "Box.unused"))
                .startsWith("Uses of Box.unused (field): 0 in main sources, 0 in tests (0 writes, 0 reads).")
                .contains("Complete:")
                .contains("Looked at: its Java uses, accessors (none declared), anchors it maps to (column, json, bean,"
                        + " config; found none)")
                .endsWith("No Java source uses it.");
    }

    @Test
    void aTypesUsesAreEveryResolvedNameButItsDeclaration(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        String answer = callers(root, "Box");
        assertThat(answer)
                .startsWith("Uses of Box (class): 4 in main sources, 0 in tests.")
                .contains("Use.twice()  src/main/java/com/acme/shop/Use.java:5")
                .contains("Use.make()  src/main/java/com/acme/shop/Use.java:11,12")
                .doesNotContain("Box.java");
    }

    @Test
    void anAnnotationsUsesAreTheDeclarationsItAnnotates(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThat(callers(root, "@Marker"))
                .startsWith("Uses of @Marker (annotation): 1 in main sources, 0 in tests.")
                .contains("Use  src/main/java/com/acme/shop/Use.java:3", "L3| @Marker");
    }

    @Test
    void aConstructorsCallersComeFromTheCompiledClasses(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        String answer = callers(root, "Box.Box");
        assertThat(answer)
                .startsWith("Usages of Box's constructors: 1 in main sources, 0 in tests.")
                .contains("Use.make()  src/main/java/com/acme/shop/Use.java:12", "L12| return new Box(3);");
        assertThat(callers(root, "Box.new")).isEqualTo(answer);
    }

    @Test
    void aSourceTheIndexDoesNotDescribeIsMatchedByNameAndTheAnswerIsNotComplete(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        Path kt = Files.createDirectories(root.resolve("src/main/kotlin/com/acme/shop"));
        Files.writeString(kt.resolve("Wrap.kt"), """
                package com.acme.shop

                class Wrap(val box: Box) {
                    fun grow() {
                        box.size = box.size + 1
                    }
                }
                """);
        String answer = callers(root, "Box.size");
        assertThat(answer)
                .contains("Not complete: 1 source javac has not indexed as they stand")
                .contains("Wrap.grow()  src/main/kotlin/com/acme/shop/Wrap.kt:5 (inferred)")
                .contains("Use.twice()  src/main/java/com/acme/shop/Use.java:6,7\n");
    }

    @Test
    void anEditedJavaSourceIsMatchedByName(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        Path use = root.resolve(PKG + "Use.java");
        Files.writeString(
                use, Files.readString(use).replace("return b.size + b.size;", "return \"size\".length() + 'x';"));
        String answer = callers(root, "Box.size", Set.of(PKG + "Use.java"));
        assertThat(answer)
                .contains("Not complete: 1 source")
                .contains("Use.java:6,7 (inferred)")
                .doesNotContain("L8|");
    }

    @Test
    void anInitializerIsAWriteAndAConstantsUseIsUnderThatConstantsType(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThat(callers(root, "Box.LIMIT"))
                .startsWith("Uses of Box.LIMIT (field): 2 in main sources, 0 in tests (1 write, 1 read).")
                .contains("  writes:\n    Box.LIMIT  src/main/java/com/acme/shop/Box.java:13")
                .contains("  reads:\n    Use.MAX  src/main/java/com/acme/shop/Use.java:15");
    }

    @Test
    void aMethodAddressIsStillAnsweredWithItsCallers(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThat(callers(root, "Use.make")).startsWith("Usages of Use.make()");
    }
}
