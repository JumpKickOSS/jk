// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.DeclaredPins;
import cc.jumpkick.mvn.FrontendCollector;
import cc.jumpkick.mvn.FrontendFiles;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.MarkdownReports;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /**
     * What an import did: its exit code, each issue the report lists, the error behind a non-zero
     * exit, the files it wrote for the user (or a dry run would write), and the report it wrote
     * ({@code null} when none).
     */
    public record Outcome(
            int exit,
            List<String> issues,
            @Nullable String error,
            List<Path> wrote,
            @Nullable Path report) {
        public Outcome {
            issues = issues == null ? List.of() : List.copyOf(issues);
            wrote = wrote == null ? List.of() : List.copyOf(wrote);
        }

        static Outcome failed(int exit, @Nullable String error) {
            return new Outcome(exit, List.of(), error, List.of(), null);
        }
    }

    /** The flag that lets the import overwrite a {@code jk.toml} that is already there. */
    public static final String OVERWRITE_FLAG = "--overwrite";

    /**
     * Convert {@code source} (a {@code pom.xml}, or a Gradle build or settings script) and write
     * {@code jk.toml} files. {@code poms} resolves the parents and BOMs a POM inherits; {@code
     * gradle} reads a Gradle build; {@code progress} hears each stage of a Gradle read. {@code exit}
     * 0 success, {@link Exit#USAGE} a missing argument or an unrecognised source, {@link
     * Exit#CANT_CREATE} when any manifest the import would write is already there and {@code overwrite}
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
            boolean overwrite,
            boolean dryRun,
            @Nullable Path report,
            Consumer<String> progress,
            PinRaise raise) {
        if (source == null || out == null) {
            return Outcome.failed(Exit.USAGE, "import requires source and out");
        }
        try {
            String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
            JkBuild root;
            Map<String, JkBuild> modules = new LinkedHashMap<>();
            ImportReport importReport;
            FrontendFiles frontend = FrontendFiles.NONE;
            List<UserConfigEditor.RepositoryTable> machineRepositories = List.of();

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
                machineRepositories = result.machineRepositories();
            } else {
                return Outcome.failed(Exit.USAGE, "unrecognised source: " + source.getFileName());
            }

            Path effectiveBaseDir = baseDir != null ? baseDir : Objects.requireNonNull(source.getParent());
            Map<Path, JkBuild> manifests = new LinkedHashMap<>();
            manifests.put(out, root);
            for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
                manifests.put(effectiveBaseDir.resolve(e.getKey()).resolve(ManifestPaths.MANIFEST), e.getValue());
            }
            if (!overwrite && !dryRun) {
                String refusal = overwriteRefusal(manifests.keySet(), effectiveBaseDir);
                if (refusal != null) return Outcome.failed(Exit.CANT_CREATE, refusal);
            }
            List<Path> wrote = new ArrayList<>();
            if (dryRun) {
                wrote.addAll(manifests.keySet());
                wrote.addAll(frontend.rewrites().keySet());
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
                    // Spell handles by the catalog the parse of this file reads, downloaded and project
                    // layers included, so a shortened entry names the same coordinate.
                    LibraryCatalog catalog = LibraryCatalog.forProject(dir != null ? dir : effectiveBaseDir);
                    Files.writeString(
                            e.getKey(), JkBuildRenderer.render(e.getValue(), catalog), StandardCharsets.UTF_8);
                    wrote.add(e.getKey());
                }
                for (Map.Entry<Path, String> e : frontend.rewrites().entrySet()) {
                    Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
                    wrote.add(e.getKey());
                }
                importReport = machineRepositories(importReport, machineRepositories, JkDirs.userConfigFile(), wrote);
                importReport = raisedAt(importReport, raise, out, modules.isEmpty());
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
            }
            List<String> issues = importReport.issues().stream()
                    .map(ImportReport.Issue::message)
                    .toList();
            return new Outcome(0, issues, null, wrote, reportTarget);
        } catch (IOException e) {
            return Outcome.failed(1, e.getMessage());
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

    /**
     * Raises the written project's exact pins that a highest-wins solve needs higher, returning one
     * line per raise. The engine supplies it; the import alone cannot solve.
     */
    @FunctionalInterface
    public interface PinRaise {
        List<String> raise(Path lockDir) throws Exception;

        PinRaise NONE = lockDir -> List.of();
    }

    /**
     * The pin check over the written root manifest. The check reads a directory's {@code jk.toml};
     * a single project written under another name ({@code jk import -t other.toml}) is checked as a
     * {@code jk.toml} in a scratch directory and written back, so it matches what an import in place
     * writes.
     */
    /**
     * {@code report} after appending each of {@code tables} to {@code configFile}, with a row for a
     * table left out because the file already declares a repository of that name.
     */
    private static ImportReport machineRepositories(
            ImportReport report, List<UserConfigEditor.RepositoryTable> tables, Path configFile, List<Path> wrote)
            throws IOException {
        if (tables.isEmpty()) return report;
        ImportReport.Builder out = ImportReport.builder();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) out.error(issue.message());
            else out.warning(issue.message());
        }
        boolean written = false;
        for (UserConfigEditor.RepositoryTable table : tables) {
            if (UserConfigEditor.addRepository(configFile, table)) {
                written = true;
            } else if (!UserConfigEditor.declaresRepositoryAt(configFile, table)) {
                out.warning(table.name() + ": already in " + configFile + "; left as is. Check its credential for "
                        + table.url());
            }
        }
        if (written) wrote.add(configFile);
        return out.build();
    }

    private static ImportReport raisedAt(ImportReport report, PinRaise raise, Path out, boolean single)
            throws IOException {
        Path dir = Objects.requireNonNull(out.toAbsolutePath().getParent());
        if (!single || out.getFileName().toString().equals(ManifestPaths.MANIFEST)) return raised(report, raise, dir);
        Path scratch = Files.createTempDirectory("jk-import-");
        try {
            Path manifest = Files.copy(out, scratch.resolve(ManifestPaths.MANIFEST));
            ImportReport checked = raised(report, raise, scratch);
            Files.copy(manifest, out, StandardCopyOption.REPLACE_EXISTING);
            return checked;
        } finally {
            PathUtil.deleteRecursively(scratch);
        }
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
            failed = "Pins not checked against their dependencies ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    + "); jk lock names any pin set too low";
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
