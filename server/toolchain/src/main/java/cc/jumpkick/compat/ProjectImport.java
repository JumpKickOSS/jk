// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.gradle.GradleImporter;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.PomImporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maven/Gradle → {@code jk.toml} conversion. Runs in the engine (the registry host) so import
 * does not need sibling plugin manifests on a worker classpath.
 */
public final class ProjectImport {

    private ProjectImport() {}

    public record Outcome(int exit, int warnings, @Nullable String error, List<Path> wrote) {
        public Outcome {
            wrote = wrote == null ? List.of() : List.copyOf(wrote);
        }
    }

    /**
     * Convert {@code source} (a {@code pom.xml} or Gradle build file) and write {@code jk.toml}
     * files. {@code exit} 0 success, {@link Exit#USAGE} a missing argument or an unrecognised
     * source, {@link Exit#CANT_CREATE} overwrite without force, 1 IO error.
     */
    public static Outcome run(
            Path source,
            Path out,
            @Nullable Path baseDir,
            @Nullable Path tmpDir,
            boolean force,
            @Nullable Path report) {
        if (source == null || out == null) {
            return new Outcome(Exit.USAGE, 0, "import requires source and out", List.of());
        }
        try {
            String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
            JkBuild root;
            Map<String, JkBuild> modules = new LinkedHashMap<>();
            ImportReport importReport;

            if (filename.endsWith("pom.xml")) {
                PomImporter.WorkspaceImportResult result = PomImporter.importWorkspace(source);
                root = result.root();
                modules.putAll(result.modules());
                importReport = result.report();
            } else if (filename.equals("build.gradle") || filename.equals("build.gradle.kts")) {
                GradleImporter.Result result = GradleImporter.importFrom(source);
                root = result.jkBuild();
                importReport = result.report();
            } else {
                return new Outcome(Exit.USAGE, 0, "unrecognised source: " + source.getFileName(), List.of());
            }

            List<Path> wrote = new ArrayList<>();
            Files.writeString(out, JkBuildRenderer.render(root), StandardCharsets.UTF_8);
            wrote.add(out);

            Path effectiveBaseDir = baseDir != null ? baseDir : Objects.requireNonNull(source.getParent());
            for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
                Path moduleJkBuild = effectiveBaseDir.resolve(e.getKey()).resolve(ManifestPaths.MANIFEST);
                if (Files.exists(moduleJkBuild) && !force) {
                    return new Outcome(
                            Exit.CANT_CREATE, 0, "would overwrite " + moduleJkBuild + " — pass --force", wrote);
                }
                Files.writeString(moduleJkBuild, JkBuildRenderer.render(e.getValue()), StandardCharsets.UTF_8);
                wrote.add(moduleJkBuild);
            }

            Path reportTarget = report;
            if (reportTarget == null && tmpDir != null) {
                var proj = root.project();
                String coord = proj.group() + "-" + proj.name() + "-" + proj.version();
                for (int n = 1; ; n++) {
                    Path candidate = tmpDir.resolve(coord + "-" + n + "-" + source.getFileName() + "-import.md");
                    if (!Files.exists(candidate)) {
                        reportTarget = candidate;
                        break;
                    }
                }
            }
            if (reportTarget != null) {
                Path rDir = reportTarget.getParent();
                if (rDir != null) Files.createDirectories(rDir);
                Files.writeString(reportTarget, importReport.renderMarkdown(source.toString()), StandardCharsets.UTF_8);
                wrote.add(reportTarget);
            }
            return new Outcome(0, importReport.issues().size(), null, wrote);
        } catch (IOException e) {
            return new Outcome(1, 0, e.getMessage(), List.of());
        }
    }
}
