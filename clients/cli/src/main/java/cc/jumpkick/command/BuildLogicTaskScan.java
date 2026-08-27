// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.BuildLogicToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Offline discovery of project build-logic task names for {@code jk tasks}. Scans the directory
 * {@link BuildLogicToml} resolves — the same one the engine runs — without executing: stem scripts
 * ({@code before-compile.groovy} / {@code .kts}).
 */
final class BuildLogicTaskScan {

    /** Mirrors engine {@code BuildLogicScripts} stems (keep in sync). */
    private static final List<String> SCRIPT_STEMS =
            List.of("before-compile", "after-compile", "after-resources", "before-package");

    private BuildLogicTaskScan() {}

    /** Task names (no {@code build-logic:} prefix); empty when no logic dir / no hits. */
    static List<String> discoverNames(Path projectDir) {
        var logic = BuildLogicToml.resolve(projectDir);
        if (logic.isEmpty()) return List.of();
        Path logicDir = logic.get().dir();
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> top = Files.list(logicDir)) {
            List<Path> files = top.filter(Files::isRegularFile).toList();
            // .kts wins the same stem: collect kts names first, then groovy that isn't claimed.
            Set<String> kts = new LinkedHashSet<>();
            for (Path p : files) {
                String file = p.getFileName().toString();
                if (!file.endsWith(".kts")) continue;
                stemName(file.substring(0, file.length() - ".kts".length())).ifPresent(kts::add);
            }
            names.addAll(kts);
            for (Path p : files) {
                String file = p.getFileName().toString();
                if (!file.endsWith(".groovy")) continue;
                stemName(file.substring(0, file.length() - ".groovy".length()))
                        .filter(n -> !kts.contains(n))
                        .ifPresent(names::add);
            }
        } catch (IOException ignored) {
            // listing is best-effort for `jk tasks`
        }
        return new ArrayList<>(names);
    }

    private static Optional<String> stemName(String stem) {
        String n = stem.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (String s : SCRIPT_STEMS) {
            if (n.equals(s) || n.startsWith(s + "-")) return Optional.of(n);
        }
        return Optional.empty();
    }
}
