// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.BuildLogicToml;
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

/**
 * Offline discovery of project build-logic task names for {@code jk tasks}. Scans the directory
 * {@link BuildLogicToml} resolves — the same one the engine runs — without compiling: stem scripts
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
        // Same resolution the engine will use — see BuildLogicToml for why the CLI can share it.
        var logic = BuildLogicToml.resolve(projectDir);
        if (logic.isEmpty()) return List.of();
        Path logicDir = logic.get().dir();
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
}
