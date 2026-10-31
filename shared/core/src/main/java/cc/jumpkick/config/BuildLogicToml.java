// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where a project's build logic lives: {@code [build].logic}, else {@value #DEFAULT_DIR}/.
 *
 * <p>{@value #JK_DIR}/ holds a project's checked-in jk state, one subdirectory per concern; only
 * {@value #DEFAULT_DIR}/ is build logic. A script left at the top of {@value #JK_DIR}/ is not
 * read ({@link #strayScripts}).
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
 * #DEFAULT_DIR}/", identically on both sides.
 *
 * <p>Nothing else in {@code [build]} comes through here. The rest of the table feeds compile and
 * package action keys and stays with the full parser, where a misread would be a correctness bug
 * rather than a defaulted directory.
 */
public final class BuildLogicToml {

    /** A project's checked-in jk state: one subdirectory per concern, none of it generated. */
    public static final String JK_DIR = ".jk";

    /** The build scripts' directory, the default {@code [build].logic}. */
    public static final String DEFAULT_DIR = JK_DIR + "/scripts";

    private static final String LOGIC = "build.logic";

    /**
     * {@code --scripts-only} with no {@code guard} stem at the invocation root. Canonical paths the
     * lookup covers; {@code [build].logic} still resolves first when set.
     */
    public static final String NO_GUARD_SCRIPTS =
            "no guard scripts in this project (looked for .jk/scripts/guard.{kts,groovy} at the root)";

    private BuildLogicToml() {}

    /** A project's resolved build-logic directory. */
    public record Logic(Path dir) {}

    /**
     * Resolve build logic for {@code projectDir}: empty when it is switched off
     * ({@code logic = false} / {@code no} / {@code 0} / {@code off} / {@code none} / {@code
     * disable}) or when the directory does not exist.
     *
     * @throws IllegalStateException when {@code [build].logic} points outside the project root
     */
    public static Optional<Logic> resolve(Path projectDir) {
        if (projectDir == null) return Optional.empty();
        Path root = projectDir.toAbsolutePath().normalize();
        TomlScan scan = TomlScan.scan(ManifestPaths.manifestIn(root), LOGIC);

        String declared = scan.get(LOGIC);
        if (declared != null && !declared.isBlank()) {
            if (isOff(declared)) return Optional.empty();
            return Optional.ofNullable(declaredDir(root, declared.trim()));
        }
        Path dir = root.resolve(DEFAULT_DIR);
        return Files.isDirectory(dir) ? Optional.of(new Logic(dir)) : Optional.empty();
    }

    /** {@code logicDir} as a project spells it: {@code .jk/scripts} for the default, else its own name. */
    public static String shown(Path logicDir) {
        Path parent = logicDir.getParent();
        String name = String.valueOf(logicDir.getFileName());
        boolean underJk = parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equals(JK_DIR);
        return underJk ? JK_DIR + "/" + name : name;
    }

    /**
     * The script files at the top of {@code projectDir}'s {@value #JK_DIR}/, which no build reads:
     * scripts live in {@value #DEFAULT_DIR}/. Sorted names; empty when there are none.
     */
    public static List<String> strayScripts(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize().resolve(JK_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (f, attrs) -> {
                String n = f.getFileName().toString();
                if (attrs.isRegularFile() && (n.endsWith(".kts") || n.endsWith(".groovy"))) out.add(n);
                return true;
            });
        } catch (IOException unreadable) {
            return List.of();
        }
        out.sort(null);
        return out;
    }

    /** The one line a build says about {@link #strayScripts}, or {@code null} when there are none. */
    public static @Nullable String strayScriptsNote(Path projectDir) {
        List<String> stray = strayScripts(projectDir);
        if (stray.isEmpty()) return null;
        return String.join(", ", stray) + (stray.size() == 1 ? " is" : " are") + " at the top of " + JK_DIR
                + "/ and will not run: build scripts live in " + DEFAULT_DIR + "/";
    }

    /**
     * The build-logic directory of {@code moduleDir} as a list: the one {@link #resolve} finds, or
     * none. For a reader that hashes or scans it; a {@code [build].logic} that {@link #resolve}
     * refuses is refused by the build itself, with its message.
     */
    public static List<Path> dirs(Path moduleDir) {
        try {
            return resolve(moduleDir).map(l -> List.of(l.dir())).orElse(List.of());
        } catch (IllegalStateException refused) {
            return List.of();
        }
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
        Path dir = root.resolve(logicRel).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalStateException("[build].logic must stay under the project root: " + logicRel);
        }
        if (!Files.isDirectory(dir)) return null;
        return new Logic(dir);
    }
}
