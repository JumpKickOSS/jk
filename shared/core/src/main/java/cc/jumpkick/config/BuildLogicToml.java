// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a project's build logic lives: {@code [build].logic} (default {@value #DEFAULT_DIR}) and
 * {@code [build].logic-main}.
 *
 * <h2>Why this is a {@link TomlScan} reader and not a {@link JkBuildParser} table</h2>
 *
 * Two callers need the answer and they must not be able to disagree. The engine runs the logic
 * ({@code BuildLogicSupport}); the CLI <em>lists</em> the task names it would run ({@code jk
 * tasks}). If the two resolve different directories, {@code jk tasks} advertises tasks the build
 * will never execute — and that is exactly what happened, because each had its own copy of the key
 * name, the off-spellings, and the containment rule.
 *
 * <p>The CLI cannot reach the full parser: {@code checkCliNoParseTypes} keeps {@code JkBuildParser}
 * — and, with it, tomlj and ANTLR — off the native image's reachability graph (JK-2151). So the one
 * substrate both sides can share is the line scanner. That is a real narrowing and it is stated
 * rather than hidden: a {@code logic} key written as part of an inline {@code build = { … }} table
 * reads as absent and the default directory applies. {@code TomlScan}'s contract is that exotic
 * TOML degrades to absent, never to a wrong value, so the failure mode is "jk uses
 * {@value #DEFAULT_DIR}", identically on both sides.
 *
 * <p>Nothing else in {@code [build]} comes through here. The rest of the table feeds compile and
 * package action keys and stays with the full parser, where a misread would be a correctness bug
 * rather than a defaulted directory.
 */
public final class BuildLogicToml {

    /** Default project-relative directory for build logic sources (dot-dir: not product noise). */
    public static final String DEFAULT_DIR = ".jk-build";

    private static final String LOGIC = "build.logic";
    private static final String LOGIC_MAIN = "build.logic-main";

    private BuildLogicToml() {}

    /** A project's resolved build-logic location. {@code main} is null unless declared. */
    public record Logic(Path dir, String main) {}

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
        TomlScan scan = TomlScan.scan(root.resolve(ManifestPaths.MANIFEST), LOGIC, LOGIC_MAIN);

        String logicRel = DEFAULT_DIR;
        String declared = scan.get(LOGIC);
        if (declared != null && !declared.isBlank()) {
            if (isOff(declared)) return Optional.empty();
            logicRel = declared.trim();
        }
        Path dir = root.resolve(logicRel).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalStateException("[build].logic must stay under the project root: " + logicRel);
        }
        if (!Files.isDirectory(dir)) return Optional.empty();

        String main = scan.get(LOGIC_MAIN);
        return Optional.of(new Logic(dir, main == null || main.isBlank() ? null : main.trim()));
    }

    /**
     * The off spellings. {@link EnvValues#parseBool} owns the general truth set; {@code none} and
     * {@code disable} are this key's own two extras.
     */
    private static boolean isOff(String raw) {
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return EnvValues.parseBool(n).filter(on -> !on).isPresent() || n.equals("none") || n.equals("disable");
    }
}
