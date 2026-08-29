// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stem-convention discovery for project build-logic scripts under {@code .jk/}.
 *
 * <p>Top-level files only (not recursive). Recognized stems map to {@link BuildLogicAnchor}:
 *
 * <pre>
 *   before-compile.groovy|.kts   → BEFORE_COMPILE (stage generate)
 *   after-compile.groovy|.kts    → AFTER_COMPILE
 *   after-resources.groovy|.kts  → AFTER_RESOURCES
 *   before-package.groovy|.kts   → BEFORE_PACKAGE
 *   after-build.groovy|.kts      → AFTER_BUILD (workspace root only)
 * </pre>
 *
 * <p>The first four are module anchors and the last is the root's; neither set is legal in the
 * other's scope. {@link BuildLogicSupport} enforces that, because only it knows which one it is
 * looking at.
 *
 * <p>Optional suffix for multiple scripts at one anchor: {@code before-compile-collections.groovy}
 * → task name {@code before-compile-collections}, same anchor. Underscores accepted as aliases
 * ({@code before_compile.kts}). A {@code .kts} and {@code .groovy} with the same stem name: the
 * {@code .kts} runs and the {@code .groovy} is ignored.
 */
final class BuildLogicScripts {

    private static final Map<String, BuildLogicAnchor> STEMS;

    static {
        Map<String, BuildLogicAnchor> m = new LinkedHashMap<>();
        m.put("before-compile", BuildLogicAnchor.BEFORE_COMPILE);
        m.put("after-compile", BuildLogicAnchor.AFTER_COMPILE);
        m.put("after-resources", BuildLogicAnchor.AFTER_RESOURCES);
        m.put("before-package", BuildLogicAnchor.BEFORE_PACKAGE);
        m.put("after-build", BuildLogicAnchor.AFTER_BUILD);
        STEMS = Map.copyOf(m);
    }

    enum ScriptKind {
        GROOVY,
        KTS
    }

    record ScriptTask(String name, BuildLogicAnchor anchor, Path file, ScriptKind kind) {}

    private BuildLogicScripts() {}

    /** Discover top-level {@code *.groovy} / {@code *.kts} stem scripts under {@code logicDir}. */
    static List<ScriptTask> discover(Path logicDir) throws IOException {
        if (!Files.isDirectory(logicDir)) return List.of();
        Map<String, ScriptTask> byName = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logicDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String file = p.getFileName().toString();
                ScriptKind kind;
                String stem;
                if (file.endsWith(".groovy")) {
                    kind = ScriptKind.GROOVY;
                    stem = file.substring(0, file.length() - ".groovy".length());
                } else if (file.endsWith(".kts")) {
                    kind = ScriptKind.KTS;
                    stem = file.substring(0, file.length() - ".kts".length());
                } else {
                    continue;
                }
                Optional<BuildLogicAnchor> anchor = matchAnchor(stem);
                if (anchor.isEmpty()) continue;
                String name = normalizeName(stem);
                ScriptTask next = new ScriptTask(name, anchor.get(), p, kind);
                ScriptTask prev = byName.get(name);
                if (prev == null) {
                    byName.put(name, next);
                } else if (kind == ScriptKind.KTS && prev.kind() == ScriptKind.GROOVY) {
                    byName.put(name, next);
                } else if (kind == ScriptKind.GROOVY && prev.kind() == ScriptKind.KTS) {
                    // .kts already claimed this stem
                } else {
                    throw new IllegalStateException("duplicate build-logic task name: " + name + " ("
                            + prev.file().getFileName() + ", " + p.getFileName() + ")");
                }
            }
        }
        List<ScriptTask> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(ScriptTask::name));
        return out;
    }

    static Optional<BuildLogicAnchor> matchAnchor(String stem) {
        if (stem == null || stem.isBlank()) return Optional.empty();
        String n = stem.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        BuildLogicAnchor exact = STEMS.get(n);
        if (exact != null) return Optional.of(exact);
        for (Map.Entry<String, BuildLogicAnchor> e : STEMS.entrySet()) {
            String prefix = e.getKey() + "-";
            if (n.startsWith(prefix) && n.length() > prefix.length()) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Canonical task name (underscores → hyphens, lower-case). */
    static String normalizeName(String stem) {
        return stem.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static Map<String, BuildLogicAnchor> stems() {
        return STEMS;
    }
}
