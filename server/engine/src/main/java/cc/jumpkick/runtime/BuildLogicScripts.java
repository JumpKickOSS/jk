// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildLogicStems;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   guard.groovy|.kts             → GUARD (invocation root: workspace root or standalone)
 * </pre>
 *
 * <p>The first four are module anchors and the last two are root-scoped; neither set is legal in
 * the other's scope. {@code guard} is also legal on a standalone project. {@link BuildLogicSupport}
 * enforces that, because only it knows which one it is looking at.
 *
 * <p>Optional suffix for multiple scripts at one anchor: {@code before-compile-collections.groovy}
 * → task name {@code before-compile-collections}, same anchor. Underscores accepted as aliases
 * ({@code before_compile.kts}). A {@code .kts} and {@code .groovy} with the same stem name: the
 * {@code .kts} runs and the {@code .groovy} is ignored.
 */
final class BuildLogicScripts {

    /**
     * Built from {@link BuildLogicStems#ALL} — the table {@code jk tasks} reads too — so the two
     * cannot disagree. A stem added there without an anchor here fails at class load, not by
     * silently dropping the script.
     */
    private static final Map<String, BuildLogicAnchor> STEMS;

    static {
        Map<String, BuildLogicAnchor> m = new LinkedHashMap<>();
        for (String stem : BuildLogicStems.ALL) {
            m.put(
                    stem,
                    switch (stem) {
                        case "before-compile" -> BuildLogicAnchor.BEFORE_COMPILE;
                        case "after-compile" -> BuildLogicAnchor.AFTER_COMPILE;
                        case "after-resources" -> BuildLogicAnchor.AFTER_RESOURCES;
                        case "before-package" -> BuildLogicAnchor.BEFORE_PACKAGE;
                        case "after-build" -> BuildLogicAnchor.AFTER_BUILD;
                        case "guard" -> BuildLogicAnchor.GUARD;
                        default ->
                            throw new IllegalStateException(
                                    "BuildLogicStems.ALL grew '" + stem + "' without an anchor here — add the mapping");
                    });
        }
        STEMS = Map.copyOf(m);
    }

    enum ScriptKind {
        GROOVY,
        KTS
    }

    /**
     * @param always the script carries the {@code jk: always} pragma: it runs whenever its anchor
     *     runs and records neither an artifact nor a verdict — for work that judges state outside
     *     the tree (a sweep of build output), where "same inputs" says nothing about the answer
     */
    record ScriptTask(String name, BuildLogicAnchor anchor, Path file, ScriptKind kind, boolean always) {}

    /** Lines of a script inspected for the {@code jk: always} pragma; a header, not the body. */
    private static final int PRAGMA_WINDOW = 40;

    /**
     * True when a {@code //} comment in the script's first lines says {@code jk: always}. A comment,
     * so both script languages spell it the same way and no build-logic API surface is needed for
     * one bit of scheduling.
     */
    static boolean declaresAlways(Path script) throws IOException {
        try (var lines = Files.lines(script)) {
            return lines.limit(PRAGMA_WINDOW)
                    .map(String::strip)
                    .filter(l -> l.startsWith("//"))
                    .anyMatch(l -> l.substring(2).strip().equalsIgnoreCase("jk: always"));
        }
    }

    private BuildLogicScripts() {}

    /**
     * Discover top-level {@code *.groovy} / {@code *.kts} stem scripts under {@code logicDir}.
     *
     * <p>A script the engine will not run is reported, never swallowed — the same principle
     * {@code rejectMisplacedStems} states: a script that does not run and does not complain is
     * indistinguishable from one that passed. An unrecognized top-level stem warns and names the
     * valid stems (with the closest as a suggestion); a recognized stem sitting in a subdirectory
     * warns that only top-level scripts run.
     */
    static List<ScriptTask> discover(Path logicDir) throws IOException {
        if (!Files.isDirectory(logicDir)) return List.of();
        Map<String, ScriptTask> byName = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logicDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) {
                    if (Files.isDirectory(p)) warnNestedStems(logicDir, p);
                    continue;
                }
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
                if (anchor.isEmpty()) {
                    warnUnknownStem(logicDir, p, stem);
                    continue;
                }
                String name = normalizeName(stem);
                ScriptTask next = new ScriptTask(name, anchor.get(), p, kind, declaresAlways(p));
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
        return BuildLogicStems.match(stem).map(STEMS::get);
    }

    /** Canonical task name (underscores → hyphens, lower-case). */
    static String normalizeName(String stem) {
        return BuildLogicStems.normalize(stem);
    }

    /**
     * {@code <module>/.jk/<file>}: the notice's key and its subject. Keyed by the file's full path,
     * not its name — two modules with the same misspelled stem are two silent no-ops, and a
     * per-run once-only notice keyed on the name alone reported the first and swallowed the second.
     */
    private static String moduleRelative(Path logicDir, Path file) {
        Path module = logicDir.toAbsolutePath().normalize().getParent();
        String owner = module == null || module.getFileName() == null
                ? "."
                : module.getFileName().toString();
        return owner + "/" + logicDir.getFileName() + "/" + logicDir.relativize(file);
    }

    private static void warnUnknownStem(Path logicDir, Path file, String stem) {
        RunNotices.warnOnce("build-logic-unknown-stem:" + file.toAbsolutePath().normalize(), () -> {
            String suggest = BuildLogicStems.closest(stem)
                    .map(s -> " Did you mean " + s + "?")
                    .orElse("");
            return "[build] " + moduleRelative(logicDir, file)
                    + " is not a recognized build-logic stem, so it will not run." + suggest
                    + " Module stems: " + String.join(" / ", BuildLogicStems.MODULE)
                    + "; invocation-root stems: " + String.join(" / ", BuildLogicStems.ROOT) + ".";
        });
    }

    /** One level down is where a misplaced script actually lands ({@code .jk/scripts/guard.kts}). */
    private static void warnNestedStems(Path logicDir, Path subDir) {
        try {
            PathUtil.forEachChild(subDir, (p, attrs) -> {
                if (!attrs.isRegularFile()) return true;
                String file = p.getFileName().toString();
                String stem = null;
                if (file.endsWith(".groovy")) stem = file.substring(0, file.length() - ".groovy".length());
                else if (file.endsWith(".kts")) stem = file.substring(0, file.length() - ".kts".length());
                if (stem == null || BuildLogicStems.match(stem).isEmpty()) return true;
                RunNotices.warnOnce(
                        "build-logic-nested-stem:" + p.toAbsolutePath().normalize(),
                        () -> "[build] "
                                + moduleRelative(logicDir, p)
                                + " will not run: build-logic scripts are discovered at the top level of "
                                + logicDir.getFileName() + "/ only — move it up a level.");
                return true;
            });
        } catch (IOException ignored) {
            // reporting is best-effort; discovery of runnable scripts is unaffected
        }
    }
}
