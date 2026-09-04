// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where a project's build logic lives: {@code [build].logic}, else the convention directories
 * {@value #VISIBLE_DIR}/ then {@value #DEFAULT_DIR}/ ({@value #VISIBLE_DIR}/ wins when both exist).
 *
 * <h2>Why this is a {@link TomlScan} reader and not a {@link JkBuildParser} table</h2>
 *
 * Two callers need the answer and they must not be able to disagree. The engine runs the logic
 * ({@code BuildLogicSupport}); the CLI <em>lists</em> the task names it would run ({@code jk
 * tasks}). If the two resolve different directories, {@code jk tasks} advertises tasks the build
 * will never execute.
 *
 * <p>The CLI cannot reach the full parser: {@code checkCliNoParseTypes} keeps {@code JkBuildParser}
 * — and, with it, tomlj and ANTLR — off the native image's reachability graph. So the one
 * substrate both sides can share is the line scanner. That is a real narrowing and it is stated
 * rather than hidden: a {@code logic} key written as part of an inline {@code build = { … }} table
 * reads as absent and the convention directories apply. {@code TomlScan}'s contract is that exotic
 * TOML degrades to absent, never to a wrong value, so the failure mode is "jk uses {@value
 * #VISIBLE_DIR}/ or {@value #DEFAULT_DIR}/", identically on both sides.
 *
 * <p>Nothing else in {@code [build]} comes through here. The rest of the table feeds compile and
 * package action keys and stays with the full parser, where a misread would be a correctness bug
 * rather than a defaulted directory.
 */
public final class BuildLogicToml {

    /** Visible convention directory (listed in a default {@code ls}). */
    public static final String VISIBLE_DIR = "jk";

    /** Hidden convention directory. Used when {@link #VISIBLE_DIR} is absent. */
    public static final String DEFAULT_DIR = ".jk";

    /** {@link #VISIBLE_DIR} first: it wins when both convention dirs exist. */
    private static final List<String> CONVENTION_DIRS = List.of(VISIBLE_DIR, DEFAULT_DIR);

    private static final String LOGIC = "build.logic";
    private static final List<String> RETIRED_DIRS = List.of(".jk-build", "jk-build");

    /**
     * {@code --scripts-only} with no {@code gate} stem at the invocation root. Canonical paths the
     * lookup covers; {@code [build].logic} still resolves first when set.
     */
    public static final String NO_GATE_SCRIPTS =
            "no gate scripts in this project (looked for jk/gate.{kts,groovy} / .jk/gate.{kts,groovy} at the root)";

    private BuildLogicToml() {}

    /** A project's resolved build-logic directory. */
    public record Logic(Path dir) {}

    /**
     * Resolve build logic for {@code projectDir}: empty when it is switched off
     * ({@code logic = false} / {@code no} / {@code 0} / {@code off} / {@code none} / {@code
     * disable}) or when no convention directory exists and none is declared.
     *
     * @throws IllegalStateException when {@code [build].logic} points outside the project root,
     *     names a retired directory, or a leftover {@code .jk-build}/ {@code jk-build} dir exists
     */
    public static Optional<Logic> resolve(Path projectDir) {
        if (projectDir == null) return Optional.empty();
        Path root = projectDir.toAbsolutePath().normalize();
        rejectRetiredDirs(root);
        TomlScan scan = TomlScan.scan(root.resolve(ManifestPaths.MANIFEST), LOGIC);

        String declared = scan.get(LOGIC);
        if (declared != null && !declared.isBlank()) {
            if (isOff(declared)) return Optional.empty();
            return Optional.ofNullable(declaredDir(root, declared.trim()));
        }
        for (String name : CONVENTION_DIRS) {
            Path dir = root.resolve(name);
            if (Files.isDirectory(dir)) return Optional.of(new Logic(dir));
        }
        return Optional.empty();
    }

    /**
     * True when the resolved logic dir has {@code name.kts} or {@code name.groovy} (underscore
     * alias too). Suffix variants ({@code name-…}) still run when the engine discovers them.
     */
    public static boolean hasStem(Path projectDir, String name) {
        if (name == null || name.isBlank()) return false;
        Optional<Logic> logic = resolve(projectDir);
        if (logic.isEmpty()) return false;
        Path dir = logic.get().dir();
        String stem = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        String under = stem.replace('-', '_');
        return file(dir, stem + ".kts")
                || file(dir, stem + ".groovy")
                || file(dir, under + ".kts")
                || file(dir, under + ".groovy");
    }

    private static boolean file(Path dir, String name) {
        return Files.isRegularFile(dir.resolve(name));
    }

    /**
     * The off spellings. {@link EnvValues#parseBool} owns the general truth set; {@code none} and
     * {@code disable} are this key's own two extras.
     */
    private static boolean isOff(String raw) {
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return EnvValues.parseBool(n).filter(on -> !on).isPresent() || n.equals("none") || n.equals("disable");
    }

    private static @Nullable Logic declaredDir(Path root, String logicRel) {
        rejectRetiredName(logicRel);
        Path dir = root.resolve(logicRel).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalStateException("[build].logic must stay under the project root: " + logicRel);
        }
        if (!Files.isDirectory(dir)) return null;
        return new Logic(dir);
    }

    private static void rejectRetiredDirs(Path root) {
        for (String name : RETIRED_DIRS) {
            if (Files.isDirectory(root.resolve(name))) {
                throw new IllegalStateException("project build logic lives in "
                        + VISIBLE_DIR
                        + "/ or "
                        + DEFAULT_DIR
                        + "/ — rename "
                        + name
                        + "/ (no compatibility path)");
            }
        }
    }

    private static void rejectRetiredName(String logicRel) {
        Path name = Path.of(logicRel).getFileName();
        if (name != null && RETIRED_DIRS.contains(name.toString())) {
            throw new IllegalStateException("[build].logic cannot be "
                    + logicRel
                    + " — use "
                    + VISIBLE_DIR
                    + "/ or "
                    + DEFAULT_DIR
                    + "/ (no compatibility path)");
        }
    }
}
