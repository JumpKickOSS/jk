// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A module's source roots, and where under them a class's source sits: a class file names only its
 * {@code SourceFile}, so its source is that name under the class's package under one of the roots.
 */
public final class SourceRoots {

    private static final List<String> MAIN_ROOTS =
            List.of("src/main/java", "src/main/kotlin", "src/main/groovy", "src/main/scala");

    private SourceRoots() {}

    /** {@code pkg/SourceFile} for {@code c}, or {@code null} when the class file kept no source name. */
    public static @Nullable String tail(ClassFacts c) {
        String file = c.sourceFile();
        if (file == null) return null;
        String pkgPath = c.packageName().replace('.', '/');
        return (pkgPath.isEmpty() ? "" : pkgPath + "/") + file;
    }

    /** The module-relative root under which {@code tail} exists, main roots first, then every suite's; or null. */
    public static @Nullable String rootHolding(Path moduleDir, String tail) {
        boolean compact = ModuleLayout.isCompact(moduleDir);
        for (Path root : roots(moduleDir, compact)) {
            if (Files.isRegularFile(root.resolve(tail))) return rel(moduleDir, root);
        }
        return null;
    }

    /** Main source roots, then each discovered suite's, whether or not they exist. */
    public static List<Path> roots(Path moduleDir, boolean compact) {
        LinkedHashSet<Path> out = new LinkedHashSet<>(mainRoots(moduleDir, compact));
        for (String suite : TestSuites.discover(moduleDir, compact)) out.addAll(suiteRoots(moduleDir, compact, suite));
        return List.copyOf(out);
    }

    /** The main source set's roots, whether or not they exist: {@code src/} in a compact module. */
    public static List<Path> mainRoots(Path moduleDir, boolean compact) {
        if (compact) return List.of(moduleDir.resolve("src"));
        List<Path> out = new ArrayList<>(MAIN_ROOTS.size());
        for (String r : MAIN_ROOTS) out.add(moduleDir.resolve(r));
        return List.copyOf(out);
    }

    /** Every language's source root for one suite, deduplicated. */
    public static List<Path> suiteRoots(Path moduleDir, boolean compact, String suite) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
        out.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
        out.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
        out.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
        return List.copyOf(out);
    }

    /** {@code root} relative to {@code moduleDir}, {@code /}-separated. */
    public static String rel(Path moduleDir, Path root) {
        return moduleDir
                .toAbsolutePath()
                .normalize()
                .relativize(root.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
