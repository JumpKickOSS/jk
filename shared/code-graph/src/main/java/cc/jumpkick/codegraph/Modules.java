// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's modules as the graph knows them: each module's directory relative to the root
 * ({@code ""} for the root, else ending in {@code /}) and whether it uses the compact layout. A file's
 * module is the longest prefix holding it; its source set follows from where it sits in the module.
 */
public record Modules(List<Module> list) {

    public static final Modules NONE = new Modules(List.of());

    /** One module: {@code prefix} is its directory, {@code compact} its layout. */
    public record Module(String prefix, boolean compact) {}

    /** A file's source set. */
    public enum SourceSet {
        MAIN,
        TEST,
        /** Neither: a manifest, a document, a file outside every source root. */
        OTHER
    }

    public Modules {
        list = list.stream()
                .sorted(Comparator.comparingInt((Module m) -> -m.prefix().length())
                        .thenComparing(Module::prefix))
                .toList();
    }

    /** The module holding {@code rel}; {@code null} when none does. */
    public @Nullable Module of(String rel) {
        for (Module m : list) {
            if (rel.startsWith(m.prefix())) return m;
        }
        return null;
    }

    /**
     * Where {@code rel} sits: under {@code src/main/} (or a compact module's {@code src/} and {@code
     * resources/}) is main; under another {@code src/<set>/} (or a compact module's {@code
     * <suite>/src/} and {@code <suite>/resources/}) is a test suite. Without a module, the
     * conventional layout is assumed.
     */
    public SourceSet sourceSet(String rel) {
        Module m = of(rel);
        String inside = m == null ? rel : rel.substring(m.prefix().length());
        if (m == null) {
            int src = inside.indexOf("src/");
            if (src < 0) return SourceSet.OTHER;
            inside = inside.substring(src);
        }
        if (m != null && m.compact()) {
            if (inside.startsWith("src/") || inside.startsWith("resources/")) return SourceSet.MAIN;
            int slash = inside.indexOf('/');
            if (slash > 0) {
                String rest = inside.substring(slash + 1);
                if (rest.startsWith("src/") || rest.startsWith("resources/")) return SourceSet.TEST;
            }
            return SourceSet.OTHER;
        }
        if (inside.startsWith("src/main/")) return SourceSet.MAIN;
        if (inside.startsWith("src/") && inside.indexOf('/', 4) > 4) return SourceSet.TEST;
        return SourceSet.OTHER;
    }
}
