// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The language runtimes {@code jk lock} roots into a module's main graph: one per language the
 * module compiles and has sources of. The lock solves them and the module's classpaths read them,
 * so a Kotlin module's stdlib is its own even when no dependency names it.
 */
public record LanguageRuntimes(boolean groovy, boolean kotlin, boolean scala) {

    public static final String GROOVY = "org.apache.groovy:groovy";
    public static final String KOTLIN = "org.jetbrains.kotlin:kotlin-stdlib";
    public static final String SCALA = "org.scala-lang:scala3-library_3";

    /** The real Scala stdlib from 3.8, under the {@code scala3-library_3} stub; the 2.13 library before. */
    public static final String SCALA_LIBRARY = "org.scala-lang:scala-library";

    /**
     * The runtimes {@code project} at {@code projectDir} needs. A language with no sources (under
     * {@code src/} or a plugin-contributed root) needs none: a bare {@code kotlin = "…"} pin on a
     * sourceless module pins the compiler and produces no classes. With no directory every
     * declared language counts.
     */
    public static LanguageRuntimes of(JkBuild project, @Nullable Path projectDir) {
        Project p = project.project();
        Languages langs = projectDir != null
                ? Languages.resolve(p, projectDir)
                : new Languages(true, p.isKotlin(), p.isGroovy(), p.isScala());
        return new LanguageRuntimes(
                langs.groovy() && hasSources(projectDir, ".groovy"),
                langs.kotlin() && hasSources(projectDir, ".kt"),
                langs.scala() && hasSources(projectDir, ".scala"));
    }

    /** The runtime modules as {@code group:artifact}, Scala's stub and its stdlib both. */
    public List<String> modules() {
        List<String> out = new ArrayList<>(4);
        if (groovy) out.add(GROOVY);
        if (kotlin) out.add(KOTLIN);
        if (scala) {
            out.add(SCALA);
            out.add(SCALA_LIBRARY);
        }
        return out;
    }

    private static boolean hasSources(@Nullable Path projectDir, String ext) {
        if (projectDir == null) return true;
        if (Languages.anySourceUnder(projectDir.resolve("src"), ext)) return true;
        for (var root : ModuleLayoutPlugins.pluginContributedRoots(projectDir)) {
            if (Languages.anySourceUnder(projectDir.resolve(root.relative()), ext)) return true;
        }
        return false;
    }
}
