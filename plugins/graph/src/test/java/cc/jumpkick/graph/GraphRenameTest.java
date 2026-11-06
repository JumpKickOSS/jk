// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.Hashing;
import java.io.File;
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
                        "    // as Range.contains() does\n    // the list contains it\n"
                                + "    // see {@link java.util.List#contains}\n    boolean listed("));
        Path root = workspace(dir, sources);
        rename(root, "Range.contains", "includes");
        assertThat(read(root, "Shop.java"))
                .contains("// as Range.includes() does")
                .as("a bare contains in another type's file is that type's")
                .contains("// the list contains it", "{@link java.util.List#contains}");
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
        return service(root, stale, List.of());
    }

    private static GraphService.Workspace service(Path root, Set<String> stale, List<String> uncompiled)
            throws IOException {
        Path classes = root.resolve("target/classes");
        FactsIndexing.Ensured e =
                FactsIndexing.ensure(classes, FactsIndexing.indexPath(root.resolve("target"), "main"));
        return new GraphService.Workspace(
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
                uncompiled,
                ModuleReach.UNKNOWN);
    }

    private static GraphService.Request request(String address, String to) {
        return new GraphService.Request(
                "rename", List.of(address, to), "bfs", 3, 4000, List.of(), false, 8, null, false, false);
    }

    @Test
    void theModulesThatCanCallAreThoseThatSeeTheDeclaringOneThroughTheEnginesEdges() {
        Modules modules = new Modules(List.of(
                new Modules.Module("app/", false),
                new Modules.Module("core/", false),
                new Modules.Module("other/", false),
                new Modules.Module("tool/", false),
                new Modules.Module("loose/", false)));
        ModuleReach reach = ModuleReach.of(List.of("app/\tcore/", "core/", "other/", "tool/\tapp/"));
        assertThat(reach.reaching(Set.of("core/"), modules))
                .as("core, what sees it, what sees that, and a module the edges do not name; never other")
                .containsExactlyInAnyOrder("core/", "app/", "tool/", "loose/");
        assertThat(ModuleReach.UNKNOWN.reaching(Set.of("core/"), modules))
                .as("no edges: every module counts")
                .isNull();
    }

    /** A JDK supertype is asked what it declares; any other type outside the workspace may declare anything. */
    @Test
    void aSupertypeOutsideTheWorkspaceMayDeclareTheMethodUnlessTheJdkSaysItDoesNot() {
        assertThat(ModuleReach.outsideMayDeclare("java/lang/Object", "doubled")).isFalse();
        assertThat(ModuleReach.outsideMayDeclare("java/lang/Object", "toString"))
                .isTrue();
        assertThat(ModuleReach.outsideMayDeclare("java/lang/Runnable", "run")).isTrue();
        assertThat(ModuleReach.outsideMayDeclare("java/util/ArrayList", "size"))
                .as("inherited from a supertype")
                .isTrue();
        assertThat(ModuleReach.outsideMayDeclare("com/elsewhere/Base", "doubled"))
                .isTrue();
    }

    /**
     * A call through a supertype runs the override, so a module that sees only the supertype's
     * module can call the renamed method: with no compiled classes, its uses are quoted. A module
     * that sees neither is left alone.
     */
    @Test
    void aModuleThatSeesOnlyTheSupertypesModuleCanCallTheOverride(@TempDir Path root) throws IOException {
        Path shapes = javaModule(root, "shapes", Map.of("Shape.java", """
                package com.acme.shapes;

                public interface Shape {
                    int area();
                }
                """));
        Path squares = javaModule(root, "squares", Map.of("Square.java", """
                package com.acme.squares;

                public class Square implements com.acme.shapes.Shape {
                    public int area() {
                        return 4;
                    }
                }
                """));
        String user = """
                package com.acme.%s;

                class Report {
                    int total(com.acme.shapes.Shape s) {
                        return s.area();
                    }
                }
                """;
        sourceOnly(root, "report", "Report.java", user.formatted("report"));
        sourceOnly(root, "other", "Report.java", user.formatted("other"));
        compile(shapes, List.of());
        compile(squares, List.of(shapes.resolve("target/classes")));
        Modules modules = new Modules(List.of(
                new Modules.Module("", false),
                new Modules.Module("shapes/", false),
                new Modules.Module("squares/", false),
                new Modules.Module("report/", false),
                new Modules.Module("other/", false)));
        ModuleReach reach = ModuleReach.of(List.of("", "shapes/", "squares/\tshapes/", "report/\tshapes/", "other/"));
        GraphService.Workspace w = new GraphService.Workspace(
                root,
                root.resolve("target/graph"),
                root.resolve("target/jk-cache"),
                List.of(index(shapes, "shapes/"), index(squares, "squares/")),
                modules,
                new ScriptReader(null, null),
                Map.of(),
                List.of(),
                List.of(),
                null,
                List.of("report", "other"),
                reach);
        String answer = GraphService.run(w, request("Square.area", "size"), GraphBuild.Progress.NONE)
                .text();
        assertThat(answer)
                .contains("Not checked: these modules may call it but have no compiled classes.")
                .contains("report/src/main/java/com/acme/report/Report.java", "L5| return s.area();")
                .doesNotContain("other/src/main/java");
    }

    private static Path javaModule(Path root, String name, Map<String, String> sources) throws IOException {
        Path dir = root.resolve(name);
        for (Map.Entry<String, String> e : sources.entrySet()) sourceOnly(root, name, e.getKey(), e.getValue());
        return dir;
    }

    private static void sourceOnly(Path root, String module, String file, String text) throws IOException {
        String pkg = text.substring("package ".length(), text.indexOf(';')).replace('.', '/');
        Path at = Files.createDirectories(
                root.resolve(module).resolve("src/main/java").resolve(pkg));
        Files.writeString(at.resolve(file), text);
        Path manifest = root.resolve(module).resolve("jk.toml");
        if (!Files.exists(manifest)) Files.writeString(manifest, "name = \"" + module + "\"\n");
    }

    private static void compile(Path module, List<Path> classpath) throws IOException {
        Path classes = Files.createDirectories(module.resolve("target/classes"));
        List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
        if (!classpath.isEmpty()) {
            args.add("-cp");
            args.add(String.join(
                    File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        try (var files = Files.walk(module.resolve("src/main/java"))) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .isZero();
    }

    private static GraphBuild.ClassIndex index(Path module, String prefix) throws IOException {
        FactsIndexing.Ensured e = FactsIndexing.ensure(
                module.resolve("target/classes"), FactsIndexing.indexPath(module.resolve("target"), "main"));
        return new GraphBuild.ClassIndex(e.index(), prefix, "main", e.bodyDigest(), e.classes(), Set.of(), List.of());
    }

    /**
     * A module or test set with no compiled classes may hold calls the rename could not see: the
     * answer renames what the classes record, says it is not complete, and never says the task is
     * done once the test passes.
     */
    @Test
    void aRenameWithAModuleThatHasNoCompiledClassesIsNotComplete(@TempDir Path dir) throws IOException {
        Path root = workspace(
                dir,
                sources(
                        "Calc.java",
                        "package com.acme.shop;\n\npublic class Calc {\n    public int doubled(int n) {\n        return n * 2;"
                                + "\n    }\n}\n",
                        "Use.java",
                        "package com.acme.shop;\n\nclass Use {\n    int four(Calc c) {\n        return c.doubled(2);\n"
                                + "    }\n}\n"));
        String answer = GraphService.run(
                        service(root, Set.of(), List.of("tool")),
                        request("Calc.doubled", "twice"),
                        GraphBuild.Progress.NONE)
                .text();
        assertThat(answer)
                .startsWith("Renamed Calc.doubled to twice: 1 declaration, 1 call, in 2 files.")
                .contains("Every use the compiled classes record is renamed. Not complete: the note below names the"
                        + " modules with no compiled classes. Test next: run(kind=test), or jk test.")
                .doesNotContain("no search needed", "the task is done");
        assertThat(read(root, "Use.java")).contains("return c.twice(2);");
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
                                "plugins/graph/src/test/java/b/GraphToolsTest.java"),
                        true))
                .as("by directory, sorted, the whole workspace's unrelated failures left out")
                .isEqualTo("Test the test classes it edited: run(kind=test, only=plugins/graph,shared/code-graph,"
                        + " classes=GraphToolsTest), or jk test -m plugins/graph,shared/code-graph -S GraphToolsTest."
                        + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("shared/code-graph/src/main/java/a/CodeGraph.java"), true))
                .as("no test edited: the modules' suites")
                .isEqualTo("Test the modules it edited: run(kind=test, only=shared/code-graph), or jk test -m"
                        + " shared/code-graph." + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("src/main/java/Root.java"), true))
                .isEqualTo("Test next: run(kind=test), or jk test." + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("src/main/java/Root.java", "src/test/java/RootTest.java"), true))
                .as("a single-module project names its edited tests too")
                .isEqualTo("Test the test classes it edited: run(kind=test, classes=RootTest), or jk test -S RootTest."
                        + EditedTests.DONE);
        assertThat(EditedTests.next(modules, List.of("src/main/java/Root.java"), false))
                .as("calls a module with no compiled classes makes may be missing: never done")
                .isEqualTo("Test next: run(kind=test), or jk test.");
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
