// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlValues;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.tomlj.TomlTable;

/**
 * Offline discovery of project build-logic task names for {@code jk tasks}. Scans
 * {@code .jk-build/} (or {@code [build].logic}) sources without compiling — stem scripts
 * ({@code before-compile.groovy} / {@code .kts}), SPI {@code .task("name", …)} strings, and legacy
 * {@code *Build} class names.
 */
final class BuildLogicTaskScan {

    private static final Pattern SPI_TASK = Pattern.compile("\\.task\\s*\\(\\s*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern SPI_TASK_SQ = Pattern.compile("\\.task\\s*\\(\\s*'([^']+)'", Pattern.MULTILINE);

    /** Mirrors engine {@code BuildLogicScripts} stems (keep in sync). */
    private static final List<String> SCRIPT_STEMS =
            List.of("before-compile", "after-compile", "after-resources", "before-package");

    private BuildLogicTaskScan() {}

    /** Task names (no {@code build-logic:} prefix); empty when no logic dir / no hits. */
    static List<String> discoverNames(Path projectDir) {
        Path logicDir = logicDir(projectDir);
        if (logicDir == null || !Files.isDirectory(logicDir)) return List.of();
        Set<String> names = new LinkedHashSet<>();
        // Top-level stem scripts (engine only discovers non-recursive *.groovy / *.kts)
        try (Stream<Path> top = Files.list(logicDir)) {
            top.filter(Files::isRegularFile).forEach(p -> {
                String file = p.getFileName().toString();
                String stem;
                if (file.endsWith(".groovy")) {
                    stem = file.substring(0, file.length() - ".groovy".length());
                } else if (file.endsWith(".kts")) {
                    stem = file.substring(0, file.length() - ".kts".length());
                } else {
                    return;
                }
                stem = stem.trim().toLowerCase(Locale.ROOT).replace('_', '-');
                for (String s : SCRIPT_STEMS) {
                    if (stem.equals(s) || stem.startsWith(s + "-")) {
                        names.add(stem);
                        break;
                    }
                }
            });
        } catch (IOException ignored) {
            // continue with java/kotlin scan
        }
        try (Stream<Path> walk = Files.walk(logicDir)) {
            walk.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".java") || (n.endsWith(".kt") && !n.endsWith(".kts"));
                    })
                    .forEach(p -> {
                        try {
                            String src = Files.readString(p, StandardCharsets.UTF_8);
                            Matcher m = SPI_TASK.matcher(src);
                            while (m.find()) names.add(m.group(1).trim());
                            m = SPI_TASK_SQ.matcher(src);
                            while (m.find()) names.add(m.group(1).trim());
                            String file = p.getFileName().toString();
                            if (file.endsWith("Build.java") || file.endsWith("BuildMain.java")) {
                                String simple = file.substring(0, file.length() - ".java".length());
                                if (!simple.isBlank()) names.add(simple);
                            } else if (file.endsWith("Build.kt") || file.endsWith("BuildMain.kt")) {
                                String simple = file.substring(0, file.length() - ".kt".length());
                                if (!simple.isBlank()) names.add(simple);
                            }
                        } catch (IOException ignored) {
                            // skip unreadable
                        }
                    });
        } catch (IOException e) {
            return new ArrayList<>(names);
        }
        return new ArrayList<>(names);
    }

    static Path logicDir(Path projectDir) {
        if (projectDir == null) return null;
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = root.resolve(ManifestPaths.MANIFEST);
        String logicRel = ".jk-build";
        var parsed = TomlValues.parse(toml);
        if (parsed.isPresent()) {
            TomlTable build = parsed.get().getTable("build");
            if (build != null) {
                String logic = build.getString("logic");
                if (logic != null && !logic.isBlank()) {
                    String n = logic.trim().toLowerCase(Locale.ROOT);
                    // `none`/`disable` are this key's own extra spellings for off.
                    if (EnvValues.parseBool(n).filter(on -> !on).isPresent()
                            || n.equals("none")
                            || n.equals("disable")) {
                        return null;
                    }
                    logicRel = logic.trim();
                }
            }
        }
        Path logicDir = root.resolve(logicRel).normalize();
        if (!logicDir.startsWith(root)) return null;
        return Files.isDirectory(logicDir) ? logicDir : null;
    }
}
