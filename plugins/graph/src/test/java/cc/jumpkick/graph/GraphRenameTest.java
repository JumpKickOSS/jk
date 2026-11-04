// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
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
    void proseThatCanOnlyMeanTheMethodIsRenamedAndTheRestLeftAlone(@TempDir Path dir) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(RANGES);
        sources.put(
                "Range.java",
                Objects.requireNonNull(RANGES.get("Range.java"))
                        .replace(
                                "    public boolean contains(T t) {",
                                "    // contains() fails open on NaN\n    // a range contains its ends\n"
                                        + "    public boolean contains(T t) {"));
        Path root = workspace(dir, sources);
        Files.writeString(root.resolve("README.md"), "- Range.contains accepts no NaN.\n- Shop contains it.\n");
        Files.writeString(root.resolve("CHANGES.md"), "- Range.contains no longer accepts NaN.\n");
        String answer = rename(root, "Range.contains", "includes");

        assertThat(answer).contains("2 comment or doc mentions", "README.md:1");
        assertThat(read(root, "Range.java"))
                .contains("// includes() fails open on NaN")
                .as("the English word is not the method")
                .contains("// a range contains its ends");
        assertThat(Files.readString(root.resolve("README.md")))
                .isEqualTo("- Range.includes accepts no NaN.\n- Shop contains it.\n");
        assertThat(Files.readString(root.resolve("CHANGES.md")))
                .as("a changelog keeps the name it recorded")
                .isEqualTo("- Range.contains no longer accepts NaN.\n");
    }

    @Test
    void aLongProseLineIsQuotedAroundTheMatch() {
        String line = "x".repeat(500) + " Range.contains " + "y".repeat(500);
        String clipped = RenameMentions.clip(line, Pattern.compile("\\bcontains\\b"));
        assertThat(clipped).contains("Range.contains").startsWith("…").endsWith("…");
        assertThat(clipped.length()).isLessThanOrEqualTo(162);
    }

    @Test
    void recordIsAMethodNameButYieldAndKotlinKeywordsAreNot() {
        assertThat(GraphRename.identifier("record")).isTrue();
        assertThat(GraphRename.identifier("yield")).isFalse();
        assertThat(GraphRename.identifier("when")).isFalse();
        assertThat(GraphRename.identifier("class")).isFalse();
    }

    @Test
    void outsideTheTypesOwnFilesOnlyAQualifiedMentionIsRenamed(@TempDir Path dir) throws IOException {
        String shop = Objects.requireNonNull(RANGES.get("Shop.java"));
        Map<String, String> sources = new LinkedHashMap<>(RANGES);
        sources.put(
                "Shop.java",
                shop.replace(
                        "    boolean listed(",
                        "    // as Range.contains() does\n    // the list contains it\n    boolean listed("));
        Path root = workspace(dir, sources);
        rename(root, "Range.contains", "includes");
        assertThat(read(root, "Shop.java"))
                .contains("// as Range.includes() does")
                .as("a bare contains in another type's file is that type's")
                .contains("// the list contains it");
        assertThat(RenameMentions.home(PKG + "RangeTest.java", Set.of("Range"))).isTrue();
    }

    @Test
    void anAnswerWithNoProseLeftSaysSo(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, RANGES);
        assertThat(rename(root, "Range.contains", "includes"))
                .contains(
                        "No comment in the edited files, and no doc or config line naming its type, mentions contains.");
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
    void theModulesThatCanCallAreReadPastPluginTablesNoInstalledPluginOwns(@TempDir Path root) throws IOException {
        manifest(root, "core", "name = \"core\"\n\n[intellij]\nplugin-id = \"x\"\n");
        manifest(
                root,
                "app",
                "name = \"app\"\n\n[dependencies]\ncore.workspace = true\n\n[intellij]\nplugin-id = \"y\"\n");
        manifest(root, "tool", "name = \"tool\"\n\n[test-dependencies]\napp.workspace = true\n");
        manifest(root, "other", "name = \"other\"\n");
        Modules modules = new Modules(List.of(
                new Modules.Module("app/", false),
                new Modules.Module("core/", false),
                new Modules.Module("other/", false),
                new Modules.Module("tool/", false)));
        assertThat(GraphRename.reaching(root, modules, "core/src/main/java/a/Core.java"))
                .as("core, what depends on it, and what depends on that; never an unrelated module")
                .containsExactlyInAnyOrder("core/", "app/", "tool/");
    }

    private static void manifest(Path root, String module, String toml) throws IOException {
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("jk.toml"), toml);
    }

    @Test
    void theAnswerNamesTheModulesTheRenameEditedToTest() {
        Modules modules = new Modules(List.of(
                new Modules.Module("", false),
                new Modules.Module("shared/code-graph/", false),
                new Modules.Module("plugins/graph/", false)));
        assertThat(EditedTests.next(
                        modules,
                        List.of(
                                "shared/code-graph/src/main/java/a/CodeGraph.java",
                                "plugins/graph/src/main/java/b/GraphTools.java",
                                "plugins/graph/src/test/java/b/GraphToolsTest.java")))
                .as("by directory, sorted, the whole workspace's unrelated failures left out")
                .isEqualTo("Test the test classes it edited: run(kind=test, only=plugins/graph,shared/code-graph,"
                        + " classes=GraphToolsTest), or jk test -m plugins/graph,shared/code-graph -S GraphToolsTest."
                        + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("shared/code-graph/src/main/java/a/CodeGraph.java")))
                .as("no test edited: the modules' suites")
                .isEqualTo("Test the modules it edited: run(kind=test, only=shared/code-graph), or jk test -m"
                        + " shared/code-graph." + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("src/main/java/Root.java")))
                .isEqualTo("Test next: run(kind=test), or jk test." + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("src/main/java/Root.java", "src/test/java/RootTest.java")))
                .as("a single-module project names its edited tests too")
                .isEqualTo("Test the test classes it edited: run(kind=test, classes=RootTest), or jk test -S RootTest."
                        + EditedTests.DONE);
    }

    private static final Map<String, String> SIZES = sources(
            "Box.java", """
            package com.acme.shop;

            public class Box {
                public int size() {
                    return 1;
                }
            }
            """,
            "Use.java", """
            package com.acme.shop;

            import java.util.List;

            public class Use {
                int both(Box b, List<String> l) {
                    return b.size() + l.size();
                }
            }
            """);

    /** An index file for {@code file} as the compile writes it (version 2): the spans given, under the hash given. */
    private static void index(Path root, String file, String sha, String symbol, String... spans) throws IOException {
        String text = read(root, file);
        StringBuilder out = new StringBuilder(ReferenceIndex.HEADER + sha + "\n=" + symbol + "\n");
        int previous = 0;
        for (String span : spans) {
            String needle = span.substring(2);
            int at = text.indexOf(needle) + needle.indexOf("size");
            out.append(span.startsWith("d:") ? 'd' : 'r')
                    .append("0\t")
                    .append(at - previous)
                    .append('\n');
            previous = at;
        }
        Path f = root.resolve("target/incremental/classes-refs/" + PKG + file + ".refs");
        Files.createDirectories(f.getParent());
        Files.writeString(f, out);
    }

    private static String sha(Path root, String file) throws IOException {
        return Hashing.sha256Hex(read(root, file).getBytes(StandardCharsets.UTF_8));
    }

    private static final String BOX_SIZE = "M com.acme.shop.Box#size()I";

    @Test
    void withoutAnIndexABodyCallingTwoMethodsOfTheNameIsListedNotGuessed(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, SIZES);
        String answer = rename(root, "Box.size", "count");
        assertThat(answer).contains("Not renamed", "Use.java");
        assertThat(read(root, "Use.java")).contains("b.size() + l.size()");
    }

    @Test
    void theCompilesReferenceIndexMakesThatRenameExact(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, SIZES);
        index(root, "Box.java", sha(root, "Box.java"), BOX_SIZE, "d:int size()");
        index(root, "Use.java", sha(root, "Use.java"), BOX_SIZE, "r:b.size()");

        String answer = rename(root, "Box.size", "count");
        assertThat(answer)
                .startsWith("Renamed Box.size to count: 1 declaration, 1 call, in 2 files.")
                .doesNotContain("Not renamed");
        assertThat(read(root, "Box.java")).contains("public int count() {");
        assertThat(read(root, "Use.java")).contains("b.count() + l.size()");
    }

    @Test
    void anIndexThatNoLongerDescribesTheSourceIsNotUsed(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, SIZES);
        index(root, "Box.java", sha(root, "Box.java"), BOX_SIZE, "d:int size()");
        index(root, "Use.java", "0".repeat(64), BOX_SIZE, "r:b.size()");

        assertThat(rename(root, "Box.size", "count")).contains("Not renamed", "Use.java");
        assertThat(read(root, "Use.java")).contains("b.size() + l.size()");
    }

    @Test
    void aVersion2IndexDecodesSpansFromTheSymbolsNamesAndTheSource() {
        String text = "class A {\n  void size() {}\n  int n = size();\n}\n";
        List<ReferenceIndex.Span> spans = ReferenceIndex.decode(
                List.of(
                        "=M A#size()V",
                        "d0\t" + text.indexOf("size"),
                        "r0\t" + (text.lastIndexOf("size") - text.indexOf("size"))),
                text);
        assertThat(spans)
                .extracting(sp -> sp.kind() + ":" + text.substring(sp.start(), sp.end()) + "@" + sp.line())
                .containsExactly("d:size@2", "r:size@3");
        assertThat(ReferenceIndex.simpleName("T a.b.Outer$Inner")).isEqualTo("Inner");
        assertThat(ReferenceIndex.simpleName("F a.B#count")).isEqualTo("count");
    }
}
