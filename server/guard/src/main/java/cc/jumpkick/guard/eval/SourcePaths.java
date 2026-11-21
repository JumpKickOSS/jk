// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.extract.SourceRoots;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.layout.ModuleLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private SourcePaths() {}

    /** Per lane run: the module's source roots listed once and each source tail resolved once. */
    private static final class Resolver {
        private final String module;
        private final @Nullable Path moduleDir;
        private final Map<String, String> resolved = new ConcurrentHashMap<>();
        private volatile @Nullable List<Path> roots;

        Resolver(String module, @Nullable Path moduleDir) {
            this.module = module;
            this.moduleDir = moduleDir;
        }

        String resolve(String tail) {
            return resolved.computeIfAbsent(tail, t -> {
                Path dir = moduleDir;
                String root = null;
                if (dir != null) {
                    List<Path> listed = roots;
                    if (listed == null) roots = listed = SourceRoots.roots(dir, ModuleLayout.isCompact(dir));
                    for (Path r : listed) {
                        if (Files.isRegularFile(r.resolve(t))) {
                            root = SourceRoots.rel(dir, r);
                            break;
                        }
                    }
                }
                return prefix(module) + (root == null ? FALLBACK : root) + "/" + t;
            });
        }
    }

    /** The workspace-relative source path of {@code c} in the context's module, or {@code null}. */
    static @Nullable String of(EvalContext ctx, ClassFacts c) {
        String tail = SourceRoots.tail(c);
        if (tail == null) return null;
        return ctx.memo(Resolver.class, k -> new Resolver(k.module(), k.moduleDir()))
                .resolve(tail);
    }

    /**
     * {@code tail} under the first root of {@code moduleDir} that holds it, prefixed by the module's
     * workspace-relative path ({@code ""} at the root); under {@link #FALLBACK} when none does.
     */
    static String resolve(String module, @Nullable Path moduleDir, String tail) {
        String root = moduleDir == null ? null : SourceRoots.rootHolding(moduleDir, tail);
        return prefix(module) + (root == null ? FALLBACK : root) + "/" + tail;
    }

    /** {@code tail} under a root already known to hold it, spelled from the workspace root. */
    static String under(String module, Path moduleDir, Path root, String tail) {
        return prefix(module) + SourceRoots.rel(moduleDir, root) + "/" + tail;
    }

    private static String prefix(String module) {
        return module.isEmpty() ? "" : module + "/";
    }
}
