// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.config.BuildLogicStems;
import cc.jumpkick.config.BuildLogicToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Offline discovery of project build-logic task names for {@code jk tasks}. Scans the directory
 * {@link BuildLogicToml} resolves — the same one the engine runs — without executing: stem scripts
 * ({@code before-compile.groovy} / {@code .kts}).
 */
final class BuildLogicTaskScan {

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

    /**
     * {@link BuildLogicStems} is the engine's table too, so {@code jk tasks} can never advertise
     * a set the engine disagrees with. Root anchors are included: the scan cannot tell a root
     * from a module without parsing the manifest, which this offline path exists to avoid.
     */
    private static Optional<String> stemName(String stem) {
        return BuildLogicStems.match(stem).map(base -> BuildLogicStems.normalize(stem));
    }
}
