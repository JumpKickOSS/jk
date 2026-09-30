// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code [test] exclude-src}: test sources compile-test leaves out. A glob matches a file's path
 * under the source root it was found in (a suite's language root or an {@code extra-src} root, else
 * the module directory), and a glob starting {@code **}{@code /} matches at that root too, as
 * Maven's {@code <testExcludes>} do.
 */
final class TestSourceExcludes {

    private final List<PathMatcher> matchers;
    private final List<Path> roots;
    private final Path moduleDir;

    private TestSourceExcludes(List<PathMatcher> matchers, List<Path> roots, Path moduleDir) {
        this.matchers = matchers;
        this.roots = roots;
        this.moduleDir = moduleDir;
    }

    /** The module's excludes over the roots of {@code suites}; null when it declares none. */
    static @Nullable TestSourceExcludes of(JkBuild project, Path moduleDir, boolean compact, List<String> suites) {
        List<String> globs = project.build().testExcludeSrc();
        if (globs.isEmpty()) return null;
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/")) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
            }
        }
        List<Path> roots = new ArrayList<>();
        for (String suite : suites.isEmpty() ? List.of(TestSuites.DEFAULT) : suites) {
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
        }
        for (String rel : project.build().testExtraSrc()) roots.add(moduleDir.resolve(rel));
        List<Path> normalized = roots.stream()
                .map(r -> r.toAbsolutePath().normalize())
                .distinct()
                .toList();
        return new TestSourceExcludes(
                matchers, normalized, moduleDir.toAbsolutePath().normalize());
    }

    /** {@code sources} without the excluded files, in order. */
    List<Path> keep(List<Path> sources) {
        return sources.stream().filter(s -> !excluded(s)).toList();
    }

    private boolean excluded(Path source) {
        Path relative = underRoot(source.toAbsolutePath().normalize());
        for (PathMatcher m : matchers) {
            if (m.matches(relative)) return true;
        }
        return false;
    }

    /** {@code file} relative to the deepest root holding it, else to the module directory. */
    private Path underRoot(Path file) {
        Path best = null;
        for (Path root : roots) {
            if (file.startsWith(root)
                    && !file.equals(root)
                    && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        if (best != null) return best.relativize(file);
        return file.startsWith(moduleDir) ? moduleDir.relativize(file) : file;
    }
}
