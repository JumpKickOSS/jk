// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * that leaves out a language whose sources exist is what {@link #undeclaredWithSources} names.
 */
public record Languages(boolean java, boolean kotlin, boolean groovy, boolean scala) {

    /** Unset Scala flag. */
    public Languages(boolean java, boolean kotlin, boolean groovy) {
        this(java, kotlin, groovy, false);
    }

    public static Languages resolve(Project project, Path projectDir) {
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
     * every root.
     */
    public static List<String> undeclaredWithSources(Project project, Path projectDir) {
        Languages declared = resolve(project, projectDir);
        boolean anyDeclared = project.java() > 0 || project.isKotlin() || project.isGroovy() || project.isScala();
        if (!anyDeclared) return List.of();
        List<String> declaredKeys = new ArrayList<>();
        if (project.java() > 0) declaredKeys.add("java");
        if (declared.kotlin()) declaredKeys.add("kotlin");
        if (declared.groovy()) declaredKeys.add("groovy");
        if (declared.scala()) declaredKeys.add("scala");
        List<String> rows = new ArrayList<>();
        // The level the compile would use: the declared one, else the JDK the engine runs on.
        int release = project.javaRelease() > 0
                ? project.javaRelease()
                : Runtime.version().feature();
        if (!declared.java()) dropped(rows, projectDir, "java", ".java", "java = " + release, declaredKeys);
        if (!declared.kotlin()) dropped(rows, projectDir, "kotlin", ".kt", "kotlin = \"<version>\"", declaredKeys);
        if (!declared.groovy()) dropped(rows, projectDir, "groovy", ".groovy", "groovy = \"<version>\"", declaredKeys);
        if (!declared.scala()) dropped(rows, projectDir, "scala", ".scala", "scala = \"<version>\"", declaredKeys);
        return rows;
    }

    private static void dropped(
            List<String> rows, Path projectDir, String key, String ext, String addition, List<String> declaredKeys) {
        Path traditional = projectDir.resolve("src/main/" + key);
        String root;
        String what;
        if (anySourceUnder(traditional, ext)) {
            root = "src/main/" + key;
            what = Character.toUpperCase(key.charAt(0)) + key.substring(1) + " sources";
        } else if (anySourceUnder(projectDir.resolve("src"), ext)) {
            root = "src";
            what = ext + " sources";
        } else {
            return;
        }
        rows.add(root + " holds " + what + " this module does not compile: jk.toml declares "
                + String.join(", ", declaredKeys) + " and not " + key + " — add " + addition + " to compile them");
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
