// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.ModuleLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a bytecode lane's classes view covers its sources: every main source file has at least
 * one class in the lane's facts compiled from it (its package directory and {@code SourceFile}
 * agree). A view missing a class — an interrupted compile, a classes tree another run is rewriting,
 * a module not built yet — still reports what it sees, but it cannot prove a banked site or a
 * metric line is gone, so the lane does not tighten the baseline from it.
 *
 * <p>The match is by directory, as {@code split-package}'s generated-code check is: a Kotlin file
 * whose package differs from its folder, or a source file that declares no type at all, reads as
 * uncompiled and holds the lane's baseline where it is until the file or its folder is fixed.
 */
final class ClassesView {

    private static final List<String> SOURCE_EXTENSIONS = List.of(".java", ".kt", ".groovy", ".scala");

    private ClassesView() {}

    /**
     * The workspace-relative main source files of the lane's modules that no class in its facts was
     * compiled from, sorted; empty for a lane that reads no class index.
     */
    static List<String> uncompiled(EvalContext ctx) {
        List<Path> modules =
                switch (ctx.lane()) {
                    case MODULE -> {
                        Path dir = ctx.moduleDir();
                        yield dir == null ? List.of() : List.of(dir);
                    }
                    case WORKSPACE -> ctx.modules();
                    default -> List.of();
                };
        if (modules.isEmpty()) return List.of();
        Set<String> compiled = compiledTails(ctx.facts());
        List<String> out = new ArrayList<>();
        for (Path module : modules) {
            String rel = WorkspaceModel.rel(ctx.root(), module);
            for (Path root : SourcePaths.mainRoots(module, ModuleLayout.isCompact(module))) {
                if (!Files.isDirectory(root)) continue;
                try {
                    PathUtil.forEachReadableFile(root, (file, attrs) -> {
                        String tail = root.relativize(file)
                                .toString()
                                .replace(file.getFileSystem().getSeparator(), "/");
                        if (isClassSource(file) && !compiled.contains(tail))
                            out.add(SourcePaths.under(rel, module, root, tail));
                    });
                } catch (IOException unreadable) {
                    // A root the lane cannot list cannot vouch for its classes either.
                    out.add(SourcePaths.under(rel, module, root, ""));
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** Whether the lane reads a class index whose coverage decides if it may tighten. */
    static boolean readsClasses(Lane lane) {
        return lane == Lane.MODULE || lane == Lane.WORKSPACE;
    }

    /** {@code pkg/SourceFile} of every class in {@code facts}. */
    private static Set<String> compiledTails(FactsIndex facts) {
        Set<String> out = new HashSet<>();
        for (ClassFacts c : facts.classes().values()) {
            String tail = SourcePaths.tail(c);
            if (tail != null) out.add(tail);
        }
        return out;
    }

    /** A source file a compiler turns into a class: not a package or module descriptor, which may compile to nothing. */
    private static boolean isClassSource(Path file) {
        String name = String.valueOf(file.getFileName());
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("package-info.") || lower.startsWith("module-info.")) return false;
        for (String ext : SOURCE_EXTENSIONS) if (lower.endsWith(ext)) return true;
        return false;
    }
}
