// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The shadow manifests of a Maven build: each POM imported the way {@code jk import} imports it,
 * rendered as {@code jk.toml} text under a {@link ShadowStamp} header naming the POM files it came
 * from. A lone module gets one shadow; a reactor gets the root's, whose {@code [workspace]} lists
 * the leaves, plus one per leaf with sibling dependencies as workspace edges. Every shadow of a
 * reactor lists every POM of the tree, so an edit anywhere re-renders them all.
 */
public final class PomShadow {

    private PomShadow() {}

    /** One rendered shadow: the module it defines, its text, and that module's Tier-3 rows. */
    public record Shadow(Path moduleDir, String toml, List<String> tier3) {}

    /** True when the shadow at {@code shadow} was rendered by this jk from the POM files it lists, as they stand. */
    public static boolean isCurrent(Path shadow, Path moduleDir) {
        return ShadowStamp.isCurrent(shadow, moduleDir);
    }

    /** A lone module's shadow; the header lists its POM and the relative-path parents on disk. */
    public static Shadow render(PomImporter importer, Path pom) throws IOException {
        PomImporter.Result imported = importer.importFrom(pom);
        Path moduleDir = Objects.requireNonNull(pom.toAbsolutePath().normalize().getParent(), "module dir");
        return shadow(moduleDir, imported.jkBuild(), rows(imported.report(), null, Set.of()), ShadowStamp.chain(pom));
    }

    /**
     * A reactor's shadows, the root's first: the root carries the leaves as {@code [workspace]
     * modules}, each leaf the module {@code jk import} would write for it. The report's rows are
     * handed to the module they name ({@code [path] } prefix) and the rest to the root.
     */
    public static List<Shadow> renderReactor(PomImporter importer, Path rootPom) throws IOException {
        PomImporter.WorkspaceImportResult imported = importer.importWorkspace(rootPom);
        Path rootDir =
                Objects.requireNonNull(rootPom.toAbsolutePath().normalize().getParent(), "root dir");
        Set<Path> inputs = new LinkedHashSet<>();
        for (Path pom : imported.pomFiles()) inputs.addAll(ShadowStamp.chain(pom));
        Set<String> paths = imported.modules().keySet();
        List<Shadow> out = new ArrayList<>();
        out.add(shadow(rootDir, imported.root(), rows(imported.report(), null, paths), inputs));
        for (Map.Entry<String, JkBuild> module : imported.modules().entrySet()) {
            Path moduleDir = rootDir.resolve(module.getKey()).normalize();
            out.add(shadow(moduleDir, module.getValue(), rows(imported.report(), module.getKey(), paths), inputs));
        }
        return out;
    }

    private static Shadow shadow(Path moduleDir, JkBuild build, List<String> tier3, Collection<Path> inputs) {
        return new Shadow(moduleDir, ShadowStamp.header(moduleDir, inputs) + JkBuildRenderer.render(build), tier3);
    }

    /**
     * The Tier-3 rows of {@code report} that belong to {@code path}: those prefixed {@code [path] }
     * with the prefix removed, or for the root ({@code null}) the rows no module path prefixes.
     */
    private static List<String> rows(ImportReport report, @Nullable String path, Set<String> paths) {
        List<String> out = new ArrayList<>();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() != ImportReport.Severity.ERROR) continue;
            String message = issue.message();
            String owner = ownerOf(message, paths);
            if (path == null ? owner == null : path.equals(owner)) {
                out.add(owner == null ? message : message.substring(owner.length() + 3));
            }
        }
        return out;
    }

    private static @Nullable String ownerOf(String message, Set<String> paths) {
        if (!message.startsWith("[")) return null;
        int close = message.indexOf("] ");
        if (close < 0) return null;
        String candidate = message.substring(1, close);
        return paths.contains(candidate) ? candidate : null;
    }
}
