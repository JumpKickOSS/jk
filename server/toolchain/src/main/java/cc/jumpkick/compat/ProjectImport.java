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
import java.util.Collection;
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

    /** The flag that lets the import overwrite a {@code jk.toml} that is already there. */
    public static final String OVERWRITE_FLAG = "--overwrite";

    /**
     * Convert {@code source} (a {@code pom.xml} or Gradle build file) and write {@code jk.toml}
     * files. {@code poms} resolves the parents and BOMs a POM inherits. {@code exit} 0 success,
     * {@link Exit#USAGE} a missing argument or an unrecognised source, {@link Exit#CANT_CREATE}
     * when any manifest the import would write is already there and {@code force} is off — nothing
     * is written then, the root included, so the tree is never half converted — 1 IO error.
     */
    public static Outcome run(
            PomImporter poms,
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
                PomImporter.WorkspaceImportResult result = poms.importWorkspace(source);
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

            Path effectiveBaseDir = baseDir != null ? baseDir : Objects.requireNonNull(source.getParent());
            Map<Path, JkBuild> manifests = new LinkedHashMap<>();
            manifests.put(out, root);
            for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
                manifests.put(effectiveBaseDir.resolve(e.getKey()).resolve(ManifestPaths.MANIFEST), e.getValue());
            }
            if (!force) {
                String refusal = overwriteRefusal(manifests.keySet(), effectiveBaseDir);
                if (refusal != null) return new Outcome(Exit.CANT_CREATE, 0, refusal, List.of());
            }
            List<Path> wrote = new ArrayList<>();
            for (Map.Entry<Path, JkBuild> e : manifests.entrySet()) {
                Files.writeString(e.getKey(), JkBuildRenderer.render(e.getValue()), StandardCharsets.UTF_8);
                wrote.add(e.getKey());
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

    /**
     * The message refusing the import when any of {@code manifests} exists, naming each one
     * relative to {@code baseDir} and the flag that overwrites them; {@code null} when none exists.
     */
    static @Nullable String overwriteRefusal(Collection<Path> manifests, Path baseDir) {
        List<String> existing = new ArrayList<>();
        for (Path manifest : manifests) {
            if (!Files.exists(manifest)) continue;
            Path absolute = manifest.toAbsolutePath().normalize();
            Path base = baseDir.toAbsolutePath().normalize();
            existing.add(
                    absolute.startsWith(base)
                            ? base.relativize(absolute).toString().replace('\\', '/')
                            : absolute.toString());
        }
        if (existing.isEmpty()) return null;
        return "refusing to overwrite " + (existing.size() == 1 ? "" : existing.size() + " existing manifests: ")
                + String.join(", ", existing) + " — nothing was written; pass " + OVERWRITE_FLAG
                + " to replace " + (existing.size() == 1 ? "it" : "them") + ".";
    }
}
