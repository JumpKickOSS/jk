// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.model.Model;

/**
 * A reactor member built by {@code maven-shade-plugin} with {@code <relocations>} publishes classes
 * under the shaded package names; jk has no package relocation, so the workspace jar of that member
 * carries none of them. When another member's sources name a shaded package the compile cannot
 * succeed, and the shaded member is a Tier-3 row: the relocations, the members that import them,
 * and the remedy (build that module with Maven and depend on its artifact instead of the edge).
 */
final class ShadedSiblings {

    /** Source files whose text is read for a shaded package name. */
    private static final Set<String> SOURCE_SUFFIXES = Set.of(".java", ".kt", ".scala", ".groovy");

    private ShadedSiblings() {}

    /** One row per shaded member whose relocated packages another member's sources name. */
    static void report(List<ReactorModules.Leaf> leaves, ImportReport.Builder report) throws IOException {
        for (ReactorModules.Leaf shaded : leaves) {
            List<PackagingPlugins.Relocation> relocations =
                    PackagingPlugins.relocations(shaded.model().model());
            List<String> packages = relocations.stream()
                    .map(PackagingPlugins.Relocation::shaded)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.contains("/"))
                    .toList();
            if (packages.isEmpty()) continue;
            Map<String, Integer> importers = new LinkedHashMap<>();
            for (ReactorModules.Leaf member : leaves) {
                if (member == shaded) continue;
                int files = filesNaming(Objects.requireNonNull(member.pomFile().getParent()), packages);
                if (files > 0) importers.put(member.path(), files);
            }
            if (importers.isEmpty()) continue;
            List<PackagingPlugins.Relocation> packageRelocations = relocations.stream()
                    .filter(r -> r.shaded() != null && packages.contains(r.shaded()))
                    .toList();
            report.error("[" + shaded.path() + "] " + row(shaded, packageRelocations, importers));
        }
    }

    private static String row(
            ReactorModules.Leaf shaded, List<PackagingPlugins.Relocation> relocations, Map<String, Integer> importers) {
        Model model = shaded.model().model();
        String gav = model.getGroupId() + ":" + model.getArtifactId() + ":" + model.getVersion();
        List<String> named = new ArrayList<>();
        importers.forEach((path, files) -> named.add("`" + path + "` (" + files + (files == 1 ? " file)" : " files)")));
        String rules = relocations.stream()
                .map(PackagingPlugins.Relocation::label)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "`maven-shade-plugin` relocates " + rules + "; jk has no package relocation, so no jar this workspace"
                + " builds carries the shaded packages, and " + String.join(", ", named)
                + (named.size() == 1 ? " imports" : " import") + " them. Keep building this module with Maven —"
                + " `jk mvn -pl " + shaded.path() + " install` publishes " + gav + " into `~/.m2/repository` — and"
                + " depend on that artifact from a `file://` repository over `~/.m2/repository` in place of the"
                + " workspace edge.";
    }

    /** How many source files under {@code moduleDir/src} contain one of {@code packages}. */
    private static int filesNaming(Path moduleDir, List<String> packages) throws IOException {
        int[] count = {0};
        PathUtil.forEachRegularFile(moduleDir.resolve("src"), (file, attrs) -> {
            if (!isSource(file)) return;
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String name : packages) {
                if (text.contains(name)) {
                    count[0]++;
                    return;
                }
            }
        });
        return count[0];
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_SUFFIXES.contains(name.substring(dot));
    }
}
