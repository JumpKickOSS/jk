// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.DeclaredPins;
import cc.jumpkick.mvn.FrontendCollector;
import cc.jumpkick.mvn.FrontendFiles;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.util.MarkdownReports;
import cc.jumpkick.util.OwnerOnlyFiles;
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
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Maven/Gradle → {@code jk.toml} conversion. Runs in the engine (the registry host) so import
 * does not need sibling plugin manifests on a worker classpath; a Gradle build is read through
 * {@link GradleBuildImport}, which forks Gradle for its project model.
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
     * Convert {@code source} (a {@code pom.xml}, or a Gradle build or settings script) and write
     * {@code jk.toml} files. {@code poms} resolves the parents and BOMs a POM inherits; {@code
     * gradle} reads a Gradle build; {@code progress} hears each stage of a Gradle read. {@code exit}
     * 0 success, {@link Exit#USAGE} a missing argument or an unrecognised source, {@link
     * Exit#CANT_CREATE} when any manifest the import would write is already there and {@code force}
     * is off — nothing is written then, the root included, so the tree is never half converted — 1
     * IO error.
     */
    public static Outcome run(
            PomImporter poms,
            GradleBuildImport gradle,
            Path source,
            Path out,
            @Nullable Path baseDir,
            @Nullable Path tmpDir,
            boolean force,
            boolean dryRun,
            @Nullable Path report,
            Consumer<String> progress,
            PinRaise raise) {
        if (source == null || out == null) {
            return new Outcome(Exit.USAGE, 0, "import requires source and out", List.of());
        }
        try {
            String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
            JkBuild root;
            Map<String, JkBuild> modules = new LinkedHashMap<>();
            ImportReport importReport;
            FrontendFiles frontend = FrontendFiles.NONE;

            if (filename.endsWith("pom.xml")) {
                FrontendCollector frontends = new FrontendCollector();
                PomImporter.WorkspaceImportResult result;
                try {
                    result = poms.frontends(frontends).importWorkspace(source);
                } finally {
                    poms.frontends(FrontendCollector.OFF);
                }
                root = result.root();
                modules.putAll(result.modules());
                importReport = DeclaredPins.check(root, modules, result.report(), poms);
                frontend = frontends.files();
            } else if (GradleBuildImport.isGradleSource(filename)) {
                GradleBuildImport.Result result = gradle.importBuild(source, progress);
                root = result.root();
                modules.putAll(result.modules());
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
            if (!force && !dryRun) {
                String refusal = overwriteRefusal(manifests.keySet(), effectiveBaseDir);
                if (refusal != null) return new Outcome(Exit.CANT_CREATE, 0, refusal, List.of());
            }
            List<Path> wrote = new ArrayList<>();
            if (dryRun) {
                importReport = dryRun(importReport, manifests.keySet(), frontend, effectiveBaseDir);
                for (ImportReport.Issue issue : importReport.issues()) progress.accept(issue.message());
            } else {
                for (FrontendFiles.Move move : frontend.moves()) {
                    Path dir = move.to().getParent();
                    if (dir != null) Files.createDirectories(dir);
                    Files.move(move.from(), move.to());
                    pruneEmpty(move.from().getParent(), effectiveBaseDir);
                }
                for (Map.Entry<Path, JkBuild> e : manifests.entrySet()) {
                    Path dir = e.getKey().getParent();
                    if (dir != null) Files.createDirectories(dir);
                    Files.writeString(e.getKey(), JkBuildRenderer.render(e.getValue()), StandardCharsets.UTF_8);
                    wrote.add(e.getKey());
                }
                for (Map.Entry<Path, String> e : frontend.rewrites().entrySet()) {
                    Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
                    wrote.add(e.getKey());
                }
                importReport = raised(
                        importReport,
                        raise,
                        Objects.requireNonNull(out.toAbsolutePath().getParent()));
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
                if (rDir != null) OwnerOnlyFiles.createDirectories(rDir);
                MarkdownReports.write(reportTarget, importReport.renderMarkdown(source.toString()));
                wrote.add(reportTarget);
            }
            return new Outcome(0, importReport.issues().size(), null, wrote);
        } catch (IOException e) {
            return new Outcome(1, 0, e.getMessage(), List.of());
        }
    }

    /** Delete {@code dir} and its parents up to {@code stop} while a move left them empty. */
    private static void pruneEmpty(@Nullable Path dir, Path stop) throws IOException {
        Path base = stop.toAbsolutePath().normalize();
        for (Path d = dir;
                d != null
                        && d.toAbsolutePath().normalize().startsWith(base)
                        && !d.toAbsolutePath().normalize().equals(base);
                d = d.getParent()) {
            boolean[] empty = {true};
            PathUtil.forEachChild(d, (child, attrs) -> empty[0] = false);
            if (!empty[0]) return;
            Files.delete(d);
        }
    }

    /** {@code report} with what a dry run would have written, moved and rewritten, and that it did none of it. */
    private static ImportReport dryRun(
            ImportReport report, Collection<Path> manifests, FrontendFiles frontend, Path baseDir) {
        ImportReport.Builder out = ImportReport.builder();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) out.error(issue.message());
            else out.warning(issue.message());
        }
        for (Path m : manifests) out.warning("dry run: would write `" + relative(baseDir, m) + "`");
        for (Path r : frontend.rewrites().keySet())
            out.warning("dry run: would rewrite `" + relative(baseDir, r) + "`");
        out.warning("dry run: nothing was written or moved");
        return out.build();
    }

    private static String relative(Path baseDir, Path p) {
        Path a = p.toAbsolutePath().normalize();
        Path b = baseDir.toAbsolutePath().normalize();
        return (a.startsWith(b) ? b.relativize(a).toString() : a.toString()).replace('\\', '/');
    }

    /**
     * Raises the written project's exact pins that a highest-wins solve needs higher, returning one
     * line per raise. The engine supplies it; the import alone cannot solve.
     */
    @FunctionalInterface
    public interface PinRaise {
        List<String> raise(Path lockDir) throws Exception;

        PinRaise NONE = lockDir -> List.of();
    }

    /** {@code report} with one warning per raised pin, or one naming why the pins were not checked. */
    private static ImportReport raised(ImportReport report, PinRaise raise, Path lockDir) {
        List<String> lines;
        String failed = null;
        try {
            lines = raise.raise(lockDir);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            lines = List.of();
            failed = "the imported pins were not checked against the versions their dependencies need ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    + "); `jk lock` names any pin a dependency needs higher";
        }
        if (lines.isEmpty() && failed == null) return report;
        ImportReport.Builder out = ImportReport.builder();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) out.error(issue.message());
            else out.warning(issue.message());
        }
        for (String line : lines) out.warning(line);
        if (failed != null) out.warning(failed);
        return out.build();
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
