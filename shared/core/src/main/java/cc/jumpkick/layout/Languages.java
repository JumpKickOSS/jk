// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which languages a module compiles — one shared answer for the engine's lane wiring and the
 * resolver's runtime inject. Explicit {@code jk.toml} opt-ins win: {@code java = <int>}
 * enables Java, {@code kotlin = "<ver>"} Kotlin, {@code groovy = "<ver>"} Groovy, {@code
 * scala = "<ver>"} Scala (any combination). When <em>none</em> is declared, infer from the
 * tree — a {@code src/main/java} dir or any {@code .java} under {@code src/} enables Java (at
 * the jdk release); likewise {@code src/main/kotlin}/{@code .kt}, {@code
 * src/main/groovy}/{@code .groovy}, and {@code src/main/scala}/{@code .scala}. Files under
 * resource trees ({@code src/<slot>/resources}, compact {@code resources/}) are ignored. A
 * project with nothing to go on defaults to Java (a bare {@code jdk = N} project). A declaration
 * that leaves out a language whose sources exist is what {@link #undeclaredWithSources} names. A
 * dedicated node module ({@link NodeShape#isNodeModule}) compiles no JVM language, whatever it
 * inherits.
 */
public record Languages(boolean java, boolean kotlin, boolean groovy, boolean scala, boolean node) {

    /** No node build. */
    public Languages(boolean java, boolean kotlin, boolean groovy, boolean scala) {
        this(java, kotlin, groovy, scala, false);
    }

    /** Unset Scala flag. */
    public Languages(boolean java, boolean kotlin, boolean groovy) {
        this(java, kotlin, groovy, false, false);
    }

    /** A dedicated node module: no JVM language. */
    public static final Languages NODE = new Languages(false, false, false, false, true);

    public static Languages resolve(Project project, Path projectDir) {
        if (NodeShape.isNodeModule(project, projectDir)) return NODE;
        boolean javaDeclared = project.java() > 0;
        boolean kotlinDeclared = project.isKotlin();
        boolean groovyDeclared = project.isGroovy();
        boolean scalaDeclared = project.isScala();
        if (javaDeclared || kotlinDeclared || groovyDeclared || scalaDeclared) {
            return new Languages(javaDeclared, kotlinDeclared, groovyDeclared, scalaDeclared);
        }
        Path src = projectDir.resolve("src");
        boolean java = Files.isDirectory(projectDir.resolve("src/main/java")) || anySourceUnder(src, ".java");
        boolean kotlin = Files.isDirectory(projectDir.resolve("src/main/kotlin")) || anySourceUnder(src, ".kt");
        boolean groovy = Files.isDirectory(projectDir.resolve("src/main/groovy")) || anySourceUnder(src, ".groovy");
        boolean scala = Files.isDirectory(projectDir.resolve("src/main/scala")) || anySourceUnder(src, ".scala");
        if (!java && !kotlin && !groovy && !scala) {
            return new Languages(true, false, false, false); // nothing detected — default to Java
        }
        return new Languages(java, kotlin, groovy, scala);
    }

    /**
     * One row per language a declared language set leaves out while its sources exist: a
     * {@code kotlin =} manifest over {@code src/main/java} compiles no Java, and the row says so,
     * naming the root that holds the sources and the key that would compile them. Empty for a
     * module whose languages are inferred from the tree, and for one whose declaration covers
     * every root. The compiler keys read {@code "<version>"}; {@link #uncompiled} gives the parts.
     */
    public static List<String> undeclaredWithSources(Project project, Path projectDir) {
        List<String> rows = new ArrayList<>();
        for (Uncompiled u : uncompiled(project, projectDir)) {
            rows.add(u.row(u.java() ? "java = " + javaRelease(project) : u.key() + " = \"<version>\""));
        }
        return rows;
    }

    /**
     * The sources of each language a declared language set leaves out, in Java, Kotlin, Groovy,
     * Scala order: {@code root} is the directory the row names and {@code what} how it names them.
     * Empty for a module whose languages are inferred from the tree.
     *
     * <p>A Kotlin, Groovy or Scala row names the first source root that language's compile would
     * collect and that holds its files ({@link #compileRoots}: {@code src/main/kotlin}, {@code
     * src/test/java}, a compact module's {@code src}), with {@code inRoot} set; files only elsewhere
     * under {@code src}, such as test data in {@code src/test/data}, give a {@code src} row with it
     * clear. A Java row names {@code src/main/java}, else {@code src}.
     *
     * <p>The build fails on a Kotlin, Groovy or Scala row in a root and warns on any other: those
     * sources would compile under the key, so with no version (own or inherited from the workspace
     * root) the module builds without them and an empty jar can report success; files outside
     * every root are never compiled, and Java sources beside a declared Kotlin, Groovy or Scala set
     * are a documented, warned state.
     */
    public static List<Uncompiled> uncompiled(Project project, Path projectDir) {
        Languages declared = resolve(project, projectDir);
        boolean anyDeclared = project.java() > 0 || project.isKotlin() || project.isGroovy() || project.isScala();
        if (!anyDeclared) return List.of();
        List<Uncompiled> out = new ArrayList<>();
        if (!declared.java()) droppedJava(out, projectDir);
        boolean compact = ModuleLayout.isCompact(projectDir);
        if (!declared.kotlin()) dropped(out, projectDir, compact, "kotlin", ".kt");
        if (!declared.groovy()) dropped(out, projectDir, compact, "groovy", ".groovy");
        if (!declared.scala()) dropped(out, projectDir, compact, "scala", ".scala");
        return out;
    }

    /**
     * The source roots a {@code kotlin}, {@code groovy} or {@code scala} compile collects, in that
     * order: main's ({@code src/main/<key>} and {@code src/main/java}, or a compact module's {@code
     * src}), then each test suite's on disk ({@code src/<suite>/<key>} and {@code src/<suite>/java},
     * or compact {@code test/src} and {@code <suite>/src}). Each may not exist.
     */
    static List<Path> compileRoots(Path projectDir, boolean compact, String key) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>(
                compact
                        ? List.of(projectDir.resolve("src"))
                        : List.of(projectDir.resolve("src/main/" + key), projectDir.resolve("src/main/java")));
        LinkedHashSet<String> suites = new LinkedHashSet<>();
        suites.add(TestSuites.DEFAULT);
        suites.addAll(TestSuites.discover(projectDir, compact));
        for (String suite : suites) {
            roots.addAll(
                    switch (key) {
                        case "kotlin" -> TestSuites.kotlinRoots(projectDir, compact, suite);
                        case "groovy" -> TestSuites.groovyRoots(projectDir, compact, suite);
                        default -> TestSuites.scalaRoots(projectDir, compact, suite);
                    });
        }
        return List.copyOf(roots);
    }

    /** The level a Java row tells the module to declare: its own, else the JDK the engine runs on. */
    public static int javaRelease(Project project) {
        return project.javaRelease() > 0
                ? project.javaRelease()
                : Runtime.version().feature();
    }

    /**
     * Sources of language {@code key} the module does not compile, under {@code root}.
     *
     * @param key the {@code jk.toml} key that would compile them: {@code java}, {@code kotlin},
     *     {@code groovy} or {@code scala}
     * @param inRoot whether {@code root} is one the language's compile collects, so the key would
     *     compile them; never for a Java row
     */
    public record Uncompiled(String key, String root, String what, boolean inRoot) {

        public boolean java() {
            return "java".equals(key);
        }

        /** The row, ending with {@code addition}, the line to add ({@code kotlin = "2.4.10"}). */
        public String row(String addition) {
            return root + " has " + what + " that are not compiled; add " + addition + " to jk.toml";
        }
    }

    private static void droppedJava(List<Uncompiled> out, Path projectDir) {
        if (anySourceUnder(projectDir.resolve("src/main/java"), ".java")) {
            out.add(new Uncompiled("java", "src/main/java", "Java sources", false));
        } else if (anySourceUnder(projectDir.resolve("src"), ".java")) {
            out.add(new Uncompiled("java", "src", ".java sources", false));
        }
    }

    private static void dropped(List<Uncompiled> out, Path projectDir, boolean compact, String key, String ext) {
        for (Path root : compileRoots(projectDir, compact, key)) {
            if (!anySourceUnder(root, ext)) continue;
            String what = key.equals(nameOf(root))
                    ? Character.toUpperCase(key.charAt(0)) + key.substring(1) + " sources"
                    : ext + " sources";
            String rel = projectDir.relativize(root).toString().replace('\\', '/');
            out.add(new Uncompiled(key, rel, what, true));
            return;
        }
        if (anySourceUnder(projectDir.resolve("src"), ext)) {
            out.add(new Uncompiled(key, "src", ext + " sources", false));
        }
    }

    /**
     * True if any regular file ending in {@code ext} exists under {@code root} outside a resource
     * tree. Resource dirs ({@code src/<slot>/resources}, compact {@code resources/}, suite
     * {@code <name>/resources}) hold assets — including {@code .kt}/{@code .groovy} templates —
     * not compile sources.
     */
    public static boolean anySourceUnder(Path root, String ext) {
        if (!Files.isDirectory(root)) return false;
        for (Path file : InputTrees.of(root).withExtension(ext)) {
            if (!underResourceTree(root, file)) return true;
        }
        return false;
    }

    /**
     * Whether {@code file} sits under a module resource directory, judged only from the
     * directories between it and {@code walkRoot}. Traditional: {@code src/<slot>/resources}.
     * Compact: {@code resources/} or {@code <suite>/resources/} directly under a module dir or
     * {@code src/}. A walk rooted at a language source dir ({@code src/main/kotlin}) holds
     * packages, not suites, so a package segment named {@code resources} there is a source.
     */
    static boolean underResourceTree(Path walkRoot, Path file) {
        Path absRoot = walkRoot.toAbsolutePath().normalize();
        Path absFile = file.toAbsolutePath().normalize();
        if (!absFile.startsWith(absRoot)) return false;
        boolean languageRoot = LANGUAGE_DIRS.contains(nameOf(absRoot));
        for (Path dir = absFile.getParent(); dir != null && !dir.equals(absRoot); dir = dir.getParent()) {
            if (!"resources".equals(nameOf(dir))) continue;
            Path parent = dir.getParent();
            if (parent == null) continue;
            Path grand = parent.getParent();
            if (grand != null && "src".equals(nameOf(grand))) return true;
            if (!languageRoot && (parent.equals(absRoot) || (grand != null && grand.equals(absRoot)))) return true;
        }
        return false;
    }

    /** Directory names that hold one language's packages under {@code src/<slot>/}. */
    private static final Set<String> LANGUAGE_DIRS = Set.of("java", "kotlin", "groovy", "scala");

    private static String nameOf(Path dir) {
        Path name = dir.getFileName();
        return name == null ? "" : name.toString();
    }
}
