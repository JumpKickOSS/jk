// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphRenameTest {

    private static final String PKG = "src/main/java/com/acme/shop/";

    private static final Map<String, String> RANGES = sources(
            "Range.java", """
            package com.acme.shop;

            /** A span; see {@link #contains(Object)} and {@link #containsRange(Range)}. */
            public class Range<T extends Comparable<T>> {
                final T lo;
                final T hi;

                Range(T lo, T hi) {
                    this.lo = lo;
                    this.hi = hi;
                }

                public boolean contains(T t) {
                    return t != null && lo.compareTo(t) <= 0 && hi.compareTo(t) >= 0;
                }

                public boolean containsRange(Range<T> r) {
                    return contains(r.lo) && contains(r.hi);
                }
            }
            """,
            "IntRange.java", """
            package com.acme.shop;

            public class IntRange extends Range<Integer> {
                IntRange(int lo, int hi) {
                    super(lo, hi);
                }

                boolean hasZero() {
                    return contains(0);
                }
            }
            """,
            "Shop.java", """
            package com.acme.shop;

            import java.util.List;
            import java.util.function.Predicate;

            /** Uses {@link Range#contains}. */
            public class Shop {
                boolean open(IntRange hours, int now) {
                    return hours.contains(now)
                            || hours
                                    .contains(now + 24);
                }

                boolean named(String name, Range<String> letters) {
                    return name.contains("x") && letters.contains(name);
                }

                Predicate<Integer> within(IntRange r) {
                    return r::contains;
                }

                boolean listed(List<String> names) {
                    return names.contains("shop");
                }
            }
            """);

    @Test
    void renamesTheDeclarationEveryCallAReferenceAndDocLinks(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, RANGES);
        String answer = rename(root, "Range.contains", "includes");

        assertThat(answer)
                .startsWith("Renamed Range.contains to includes: 1 declaration, 6 calls, 2 doc links, in 3 files.");
        assertThat(read(root, "Range.java"))
                .contains("public boolean includes(T t)", "return includes(r.lo) && includes(r.hi);")
                .contains("{@link #includes(Object)}", "{@link #containsRange(Range)}", "boolean containsRange(");
        assertThat(read(root, "IntRange.java")).contains("return includes(0);");
        assertThat(read(root, "Shop.java"))
                .contains("hours.includes(now)", ".includes(now + 24)", "return r::includes;", "{@link Range#includes}")
                .as("other types' contains stay, and the method that calls both is listed, not guessed at")
                .contains("name.contains(\"x\") && letters.contains(name)", "names.contains(\"shop\")");
        assertThat(answer).contains("Not renamed", "Shop.java", "letters.contains(name)");
    }

    @Test
    void anOverrideAndACallThroughTheInterfaceAreRenamedWithIt(@TempDir Path dir) throws IOException {
        Path root = workspace(
                dir,
                sources(
                        "Pricer.java", """
                        package com.acme.shop;

                        public interface Pricer {
                            int price(String sku);
                        }
                        """,
                        "FlatPricer.java", """
                        package com.acme.shop;

                        public class FlatPricer implements Pricer {
                            @Override
                            public int price(String sku) {
                                return 3;
                            }

                            public int price(String sku, int n) {
                                return price(sku) * n;
                            }
                        }
                        """,
                        "Till.java", """
                        package com.acme.shop;

                        public class Till {
                            int total(Pricer p) {
                                return p.price("a") + new FlatPricer().price("b", 2);
                            }
                        }
                        """));

        String answer = rename(root, "FlatPricer.price(String)", "cost");

        assertThat(answer)
                .startsWith("Renamed FlatPricer.price to cost: 2 declarations, 2 calls, in 3 files.")
                .contains("no search needed. Test next: run(kind=test), or jk test.");
        assertThat(read(root, "Pricer.java")).contains("int cost(String sku);");
        assertThat(read(root, "FlatPricer.java"))
                .contains(
                        "public int cost(String sku) {",
                        "public int price(String sku, int n)",
                        "return cost(sku) * n;");
        assertThat(read(root, "Till.java")).contains("p.cost(\"a\") + new FlatPricer().price(\"b\", 2)");
    }

    @Test
    void refusesANameARelatedTypeAlreadyHasAndAnOverrideOfALibraryMethod(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, sources("Sku.java", """
                        package com.acme.shop;

                        public class Sku implements Comparable<Sku> {
                            final String id = "";

                            @Override
                            public int compareTo(Sku o) {
                                return id.compareTo(o.id);
                            }

                            public int size() {
                                return id.length();
                            }

                            public int length() {
                                return size();
                            }
                        }
                        """));

        assertThat(rename(root, "Sku.size", "length")).contains("already declares length()");
        assertThat(rename(root, "Sku.compareTo", "order")).contains("overrides a method outside the workspace");
        assertThat(read(root, "Sku.java")).contains("public int compareTo(Sku o)", "public int size()");
    }

    @Test
    void refusesWhileASourceIsNewerThanItsClasses(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, RANGES);
        String answer = GraphService.run(
                        service(root, Set.of(PKG + "Shop.java")),
                        request("Range.contains", "includes"),
                        GraphBuild.Progress.NONE)
                .text();
        assertThat(answer).contains("edited since the last build", "build first");
        assertThat(read(root, "Range.java")).contains("public boolean contains(T t)");
    }

    @Test
    void readsWrittenParameterTypes() {
        assertThat(GraphRename.paramsMatch("T", "(Ljava/lang/Comparable;)Z")).isTrue();
        assertThat(GraphRename.paramsMatch("CharSequence, int", "(Ljava/lang/CharSequence;I)I"))
                .isTrue();
        assertThat(GraphRename.paramsMatch("final String... parts", "([Ljava/lang/String;)V"))
                .isTrue();
        assertThat(GraphRename.paramsMatch("java.util.List<String>", "(Ljava/util/List;)V"))
                .isTrue();
        assertThat(GraphRename.paramsMatch("int", "(J)V")).isFalse();
        assertThat(GraphRename.identifier("includes")).isTrue();
        assertThat(GraphRename.identifier("class")).isFalse();
        assertThat(GraphRename.identifier("2x")).isFalse();
    }

    private static Map<String, String> sources(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    private static String read(Path root, String file) throws IOException {
        return Files.readString(root.resolve(PKG + file));
    }

    private static Path workspace(Path dir, Map<String, String> sources) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"
                java = 25
                """);
        Path pkg = Files.createDirectories(dir.resolve(PKG));
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = pkg.resolve(e.getKey());
            Files.writeString(f, e.getValue());
            paths.add(f.toString());
        }
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
        args.addAll(paths);
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).isZero();
        return dir;
    }

    private static String rename(Path root, String address, String to) throws IOException {
        return GraphService.run(service(root, Set.of()), request(address, to), GraphBuild.Progress.NONE)
                .text();
    }

    private static GraphService.Workspace service(Path root, Set<String> stale) throws IOException {
        Path classes = root.resolve("target/classes");
        FactsIndexing.Ensured e =
                FactsIndexing.ensure(classes, FactsIndexing.indexPath(root.resolve("target"), "main"));
        return new GraphService.Workspace(
                root,
                root.resolve("target/graph"),
                root.resolve(".jk/graph"),
                List.of(new GraphBuild.ClassIndex(e.index(), "", e.bodyDigest(), e.classes(), stale)),
                Modules.NONE,
                new ScriptReader(null, null),
                Map.of(),
                List.of(),
                List.of(),
                null);
    }

    private static GraphService.Request request(String address, String to) {
        return new GraphService.Request(
                "rename", List.of(address, to), "bfs", 3, 4000, List.of(), false, 8, null, false, false);
    }

    @Test
    void theAnswerNamesTheModulesTheRenameEditedToTest() {
        Modules modules = new Modules(List.of(
                new Modules.Module("", false),
                new Modules.Module("shared/code-graph/", false),
                new Modules.Module("plugins/graph/", false)));
        assertThat(GraphRename.testNext(
                        modules,
                        List.of(
                                "shared/code-graph/src/main/java/a/CodeGraph.java",
                                "plugins/graph/src/main/java/b/GraphTools.java",
                                "plugins/graph/src/test/java/b/GraphToolsTest.java")))
                .as("by directory, sorted, the whole workspace's unrelated failures left out")
                .isEqualTo("Test the modules it edited: run(kind=test, only=plugins/graph,shared/code-graph),"
                        + " or jk test -m plugins/graph,shared/code-graph.");
        assertThat(GraphRename.testNext(modules, List.of("src/main/java/Root.java")))
                .isEqualTo("Test next: run(kind=test), or jk test.");
    }
}
