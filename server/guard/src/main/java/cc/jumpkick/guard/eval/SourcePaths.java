// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a bytecode site's source file lives, spelled from the workspace root. A class file names
 * only its {@code SourceFile}; the path is that name under the class's package under whichever of
 * the module's source roots holds it — {@code src/main/kotlin} for Kotlin, {@code src/} in a
 * compact module, a suite root for a test class — so the report and the SARIF name a file that
 * exists. When no root on disk holds it (sources generated elsewhere, a lane without the module
 * directory) the spelling falls back to {@link #FALLBACK}, the layout every reader expects.
 */
final class SourcePaths {

    static final String FALLBACK = "src/main/java";

    private static final List<String> MAIN_ROOTS =
            List.of("src/main/java", "src/main/kotlin", "src/main/groovy", "src/main/scala");

    private SourcePaths() {}

    /** {@code pkg/SourceFile} for {@code c}, or {@code null} when the class file kept no source name. */
    static @Nullable String tail(ClassFacts c) {
        String file = c.sourceFile();
        if (file == null) return null;
        String pkgPath = c.packageName().replace('.', '/');
        return (pkgPath.isEmpty() ? "" : pkgPath + "/") + file;
    }

    /** The workspace-relative source path of {@code c} in the context's module, or {@code null}. */
    static @Nullable String of(EvalContext ctx, ClassFacts c) {
        String tail = tail(c);
        return tail == null ? null : resolve(ctx.module(), ctx.moduleDir(), tail);
    }

    /**
     * {@code tail} under the first root of {@code moduleDir} that holds it, prefixed by the module's
     * workspace-relative path ({@code ""} at the root); under {@link #FALLBACK} when none does.
     */
    static String resolve(String module, @Nullable Path moduleDir, String tail) {
        String root = moduleDir == null ? null : rootHolding(moduleDir, tail);
        return prefix(module) + (root == null ? FALLBACK : root) + "/" + tail;
    }

    /** The module-relative root under which {@code tail} exists, main roots first, then every suite's; or null. */
    static @Nullable String rootHolding(Path moduleDir, String tail) {
        boolean compact = ModuleLayout.isCompact(moduleDir);
        for (Path root : roots(moduleDir, compact)) {
            if (Files.isRegularFile(root.resolve(tail))) return rel(moduleDir, root);
        }
        return null;
    }

    /** {@code tail} under a root already known to hold it, spelled from the workspace root. */
    static String under(String module, Path moduleDir, Path root, String tail) {
        return prefix(module) + rel(moduleDir, root) + "/" + tail;
    }

    /** Main source roots, then each discovered suite's, whether or not they exist. */
    static List<Path> roots(Path moduleDir, boolean compact) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        if (compact) out.add(moduleDir.resolve("src"));
        else for (String r : MAIN_ROOTS) out.add(moduleDir.resolve(r));
        for (String suite : TestSuites.discover(moduleDir, compact)) out.addAll(suiteRoots(moduleDir, compact, suite));
        return List.copyOf(out);
    }

    /** Every language's source root for one suite, deduplicated. */
    static List<Path> suiteRoots(Path moduleDir, boolean compact, String suite) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
        out.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
        out.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
        out.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
        return List.copyOf(out);
    }

    private static String prefix(String module) {
        return module.isEmpty() ? "" : module + "/";
    }

    private static String rel(Path moduleDir, Path root) {
        return moduleDir
                .toAbsolutePath()
                .normalize()
                .relativize(root.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
