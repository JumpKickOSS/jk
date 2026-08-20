// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.glob.GlobSet;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves {@code [build] extra-resources} into concrete source→destination copies.
 *
 * <p>Table-owning first-party plugins use this to place their own {@code jk-plugin.toml} at the
 * jar root. Giter8 trees ride {@code src/main/resources/templates/}. Patterns are
 * module-relative and clamped to the workspace.
 *
 * <p>Destination shape: a match keeps its path relative to the pattern's literal prefix, placed
 * under {@code into}.
 */
final class ExtraResources {

    /** One file to copy, and where it lands relative to the output classes directory. */
    record Copy(Path source, String destination) {}

    private ExtraResources() {}

    /**
     * Every file {@code project}'s {@code extra-resources} entries select, in declaration order.
     *
     * <p>Patterns are module-relative and clamped to the workspace root (the module itself when
     * standalone), so a manifest cannot read outside the project.
     */
    static List<Copy> resolve(JkBuild project, Path moduleDir) {
        List<JkBuild.ExtraResource> declared = project.build().extraResources();
        if (declared.isEmpty()) return List.of();

        Path clamp = clampRoot(moduleDir);
        // Later entries win a destination collision, and the map keeps declaration order — so a
        // narrower entry after a broad glob can deliberately override one file.
        Map<String, Copy> byDestination = new LinkedHashMap<>();
        for (JkBuild.ExtraResource entry : declared) {
            for (GlobSet.Match match :
                    GlobSet.resolve(moduleDir, clamp, entry.from(), entry.exclude(), entry.optional())) {
                String name = entry.rename() == null || entry.rename().isBlank()
                        ? match.relative()
                        : GlobSet.applyCaptures(entry.rename(), match.captures());
                String destination = entry.into().isBlank() ? name : trimSlashes(entry.into()) + "/" + name;
                byDestination.put(destination, new Copy(match.file(), destination));
            }
        }
        return new ArrayList<>(byDestination.values());
    }

    /** The workspace root when this module belongs to one, else the module itself. */
    private static Path clampRoot(Path moduleDir) {
        try {
            return WorkspaceLocator.findRoot(moduleDir).orElse(moduleDir);
        } catch (Exception e) {
            return moduleDir;
        }
    }

    private static String trimSlashes(String s) {
        String out = s.replace('\\', '/');
        while (out.startsWith("/")) out = out.substring(1);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
