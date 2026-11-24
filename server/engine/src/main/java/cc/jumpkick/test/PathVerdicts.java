// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.test.ClassReads.Layout;
import cc.jumpkick.test.ClassReads.Where;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The verdict on each path a suite's recordings name ({@link ClassReads}), the last {@link
 * ClassReads#VERDICTS_KEPT} asked. A verdict depends on the layout alone, so every JVM of the suite
 * shares one memo; resolving and classifying a path anew on each of its reads was most of the
 * attribution's own work, since one test JVM reads a few thousand distinct paths a hundred thousand
 * times or more.
 */
final class PathVerdicts {

    /**
     * What a recorded path is: where it lies once resolved ({@code path}), what that means ({@link
     * ClassReads#classify}), and the class it loads when it lies under a class tree.
     */
    record Verdict(Path path, Where where, @Nullable String className) {}

    private final Path cwd;
    private final Layout layout;
    private final Map<String, Verdict> kept = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Verdict> eldest) {
            return size() > ClassReads.VERDICTS_KEPT;
        }
    };

    /** Verdicts on paths resolved against {@code cwd}, read under {@code layout}. */
    PathVerdicts(Path cwd, Layout layout) {
        this.cwd = cwd;
        this.layout = layout;
    }

    /** The verdict on {@code raw} as recorded; null when it names no path. */
    @Nullable
    Verdict of(String raw) {
        Verdict known = kept.get(raw);
        if (known != null) return known;
        Path p;
        try {
            p = cwd.resolve(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return null;
        }
        Where w = ClassReads.classify(p, layout);
        Verdict v = new Verdict(p, w, w == Where.LOADED ? className(p, layout) : null);
        kept.put(raw, v);
        return v;
    }

    /** The binary name of the class file {@code p} under one of the class trees. */
    private static @Nullable String className(Path p, Layout layout) {
        for (Path tree : layout.classTrees()) {
            if (!p.startsWith(tree)) continue;
            String rel = tree.relativize(p).toString().replace('\\', '/');
            return rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
        }
        return null;
    }
}
