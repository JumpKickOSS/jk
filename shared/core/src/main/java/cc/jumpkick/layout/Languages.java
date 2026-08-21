// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Which languages a module compiles — one shared answer for the engine's lane wiring and the
 * resolver's runtime inject. Explicit {@code jk.toml} opt-ins win: {@code java = <int>}
 * enables Java, {@code kotlin = "<ver>"} Kotlin, {@code groovy = "<ver>"} Groovy, {@code
 * scala = "<ver>"} Scala (any combination). When <em>none</em> is declared, infer from the
 * tree — a {@code src/main/java} dir or any {@code.java} under {@code src/} enables Java (at
 * the jdk release); likewise {@code src/main/kotlin}/{@code.kt}, {@code
 * src/main/groovy}/{@code.groovy}, and {@code src/main/scala}/{@code.scala}. A project with
 * nothing to go on defaults to Java (a bare {@code jdk = N} project).
 */
public record Languages(boolean java, boolean kotlin, boolean groovy, boolean scala) {

    /** Unset Scala flag. */
    public Languages(boolean java, boolean kotlin, boolean groovy) {
        this(java, kotlin, groovy, false);
    }

    public static Languages resolve(JkBuild.Project project, Path projectDir) {
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

    /** True if any regular file ending in {@code ext} exists anywhere under {@code root}. */
    public static boolean anySourceUnder(Path root, String ext) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(
                    p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(ext));
        } catch (IOException e) {
            return false; // unreadable tree — treat as absent
        }
    }
}
