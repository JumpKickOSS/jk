// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.TomlValues;
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
 * Offline discovery of project build-logic task names for {@code jk tasks} (JK-1057). Scans
 * {@code .jk-build/} (or {@code [build].logic}) sources without compiling — SPI {@code .task("name",
 * …)} strings and legacy {@code *Build} class names.
 */
final class BuildLogicTaskScan {

    private static final Pattern SPI_TASK = Pattern.compile("\\.task\\s*\\(\\s*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern SPI_TASK_SQ = Pattern.compile("\\.task\\s*\\(\\s*'([^']+)'", Pattern.MULTILINE);

    private BuildLogicTaskScan() {}

    /** Task names (no {@code build-logic:} prefix); empty when no logic dir / no hits. */
    static List<String> discoverNames(Path projectDir) {
        Path logicDir = logicDir(projectDir);
        if (logicDir == null || !Files.isDirectory(logicDir)) return List.of();
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(logicDir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
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
                    }
                } catch (IOException ignored) {
                    // skip unreadable
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(names);
    }

    static Path logicDir(Path projectDir) {
        if (projectDir == null) return null;
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = root.resolve("jk.toml");
        String logicRel = ".jk-build";
        var parsed = TomlValues.parse(toml);
        if (parsed.isPresent()) {
            TomlTable build = parsed.get().getTable("build");
            if (build != null) {
                String logic = build.getString("logic");
                if (logic != null && !logic.isBlank()) {
                    String n = logic.trim().toLowerCase(Locale.ROOT);
                    if (n.equals("off") || n.equals("false") || n.equals("none") || n.equals("disable")) {
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
