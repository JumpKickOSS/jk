// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.host.Log;
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
     * The build as read, before anything is written: the root manifest, the members keyed by
     * root-relative path, the report, the frontend files a POM import moves and rewrites, the
     * {@code ~/.jk/config.toml} tables, and whether the pins are versions Gradle resolved.
     */
    public record Converted(
            JkBuild root,
            Map<String, JkBuild> modules,
            ImportReport report,
            FrontendFiles frontend,
            List<UserConfigEditor.CredentialTable> machineCredentials,
            boolean pinsResolved) {}

    /** What the write stage left: the converted build, every file written (or a dry run would write), the report so far. */
    public record Written(Converted converted, List<Path> wrote, ImportReport report) {}

    /** A stage's answer: the value the next stage takes, or the outcome that ends the import. */
    public record Staged<T>(@Nullable T value, @Nullable Outcome stop) {
        static <T> Staged<T> of(T value) {
            return new Staged<>(value, null);
        }

        static <T> Staged<T> stopped(Outcome stop) {
            return new Staged<>(null, stop);
        }
    }

    /**
     * Convert {@code source} (a {@code pom.xml}, or a Gradle build or settings script) and write
     * {@code jk.toml} files: {@link #read}, {@link #write}, {@link #checkPins} and {@link #finish}
     * in turn, as the import plan runs them. {@code exit} 0 success, {@link Exit#USAGE} a missing
     * argument or an unrecognised source, {@link Exit#CANT_CREATE} when any manifest the import would
     * write is already there and {@code overwrite} is off — nothing is written then, the root
     * included, so the tree is never half converted — 1 IO error.
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
            ImportProgress progress,
            PinRaise raise) {
        Staged<Converted> read = read(poms, gradle, source, out, progress);
        if (read.stop() != null) return read.stop();
        Staged<Written> written =
                write(Objects.requireNonNull(read.value()), source, out, baseDir, overwrite, dryRun, progress);
        if (written.stop() != null) return written.stop();
        Written w = Objects.requireNonNull(written.value());
        ImportReport checked = needsPinCheck(w, dryRun) ? checkPins(w, out, raise, progress) : w.report();
        return finish(w, checked, source, tmpDir, report);
    }

    /**
     * Read and convert {@code source}. {@code poms} resolves the parents and BOMs a POM inherits;
     * {@code gradle} reads a Gradle build; {@code progress} hears the read as it goes.
     */
    public static Staged<Converted> read(
            PomImporter poms, GradleBuildImport gradle, Path source, Path out, ImportProgress progress) {
        if (source == null || out == null) {
            return Staged.stopped(Outcome.failed(Exit.USAGE, "import requires source and out"));
        }
        try {
            String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (filename.endsWith("pom.xml")) {
                progress.detail("reading " + source.getFileName() + ", its modules and parents");
                FrontendCollector frontends = new FrontendCollector();
                PomImporter.WorkspaceImportResult result;
                try {
                    result = poms.frontends(frontends).importWorkspace(source);
                } finally {
                    poms.frontends(FrontendCollector.OFF);
                }
                Map<String, JkBuild> modules = new LinkedHashMap<>(result.modules());
                progress.detail("checking declared pins");
                ImportReport report = DeclaredPins.check(result.root(), modules, result.report(), poms);
                return Staged.of(new Converted(
                        result.root(), modules, report, frontends.files(), result.machineCredentials(), false));
            }
            if (GradleBuildImport.isGradleSource(filename)) {
                GradleBuildImport.Result result = gradle.importBuild(source, progress);
                return Staged.of(new Converted(
                        result.root(),
                        new LinkedHashMap<>(result.modules()),
                        result.report(),
                        FrontendFiles.NONE,
                        result.machineCredentials(),
                        result.pinsResolved()));
            }
            return Staged.stopped(Outcome.failed(Exit.USAGE, "unrecognised source: " + source.getFileName()));
        } catch (IOException e) {
            return Staged.stopped(Outcome.failed(1, e.getMessage()));
        }
    }

    /**
     * Write every manifest, the frontend moves and rewrites and the machine's repository tables —
     * or, for a dry run, list what would be written. Refuses before writing anything when a
     * manifest exists and {@code overwrite} is off.
     */
    public static Staged<Written> write(
            Converted c,
            Path source,
            Path out,
            @Nullable Path baseDir,
            boolean overwrite,
            boolean dryRun,
            ImportProgress progress) {
        try {
            Path effectiveBaseDir = baseDir != null ? baseDir : Objects.requireNonNull(source.getParent());
            Map<Path, JkBuild> manifests = new LinkedHashMap<>();
            manifests.put(out, c.root());
            for (Map.Entry<String, JkBuild> e : c.modules().entrySet()) {
                manifests.put(effectiveBaseDir.resolve(e.getKey()).resolve(ManifestPaths.MANIFEST), e.getValue());
            }
            if (!overwrite && !dryRun) {
                String refusal = overwriteRefusal(manifests.keySet(), effectiveBaseDir);
                if (refusal != null) return Staged.stopped(Outcome.failed(Exit.CANT_CREATE, refusal));
            }
            List<Path> wrote = new ArrayList<>();
            ImportReport report = c.report();
            FrontendFiles frontend = c.frontend();
            if (dryRun) {
                wrote.addAll(manifests.keySet());
                wrote.addAll(frontend.rewrites().keySet());
                return Staged.of(new Written(c, wrote, report));
            }
            progress.grow(manifests.size()
                    + frontend.moves().size()
                    + frontend.rewrites().size());
            // Every file is written beside its place first, so a failure before the last one is
            // written leaves the tree as it was, and an import without --overwrite can run again.
            Map<Path, String> texts = new LinkedHashMap<>();
            for (Map.Entry<Path, JkBuild> e : manifests.entrySet()) {
                Path dir = e.getKey().getParent();
                // Spell handles by the catalog the parse of this file reads, downloaded and project
                // layers included, so a shortened entry names the same coordinate.
                LibraryCatalog catalog = LibraryCatalog.forProject(dir != null ? dir : effectiveBaseDir);
                texts.put(e.getKey(), JkBuildRenderer.render(e.getValue(), catalog));
            }
            texts.putAll(frontend.rewrites());
            StagedFiles staged = new StagedFiles();
            try {
                for (Map.Entry<Path, String> e : texts.entrySet()) {
                    progress.detail(display(effectiveBaseDir, e.getKey()));
                    staged.stage(e.getKey(), e.getValue());
                }
                for (FrontendFiles.Move move : frontend.moves()) {
                    staged.move(move);
                    pruneEmpty(move.from().getParent(), effectiveBaseDir);
                    progress.advance(1);
                }
                for (Path target : texts.keySet()) {
                    staged.place(target);
                    wrote.add(target);
                    progress.advance(1);
                }
            } catch (IOException | RuntimeException e) {
                staged.rollBack();
                throw e;
            }
            report = machineCredentials(report, c.machineCredentials(), JkDirs.userConfigFile(), wrote, source);
            return Staged.of(new Written(c, wrote, report));
        } catch (IOException e) {
            return Staged.stopped(Outcome.failed(1, e.getMessage()));
        }
    }

    /**
     * The files an import writes, each first written to a sibling and then renamed into place, and
     * the frontend moves made; {@link #rollBack} undoes what was done, so a failure leaves no new
     * manifest behind.
     */
    private static final class StagedFiles {
        private final Map<Path, Path> pending = new LinkedHashMap<>();
        private final List<Path> placedNew = new ArrayList<>();
        private final List<FrontendFiles.Move> moved = new ArrayList<>();

        void stage(Path target, String text) throws IOException {
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new IOException(target + " is not a file");
            }
            Path dir = target.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = target.resolveSibling("." + target.getFileName() + ".import-"
                    + ProcessHandle.current().pid());
            pending.put(target, tmp);
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
        }

        void move(FrontendFiles.Move move) throws IOException {
            Path dir = move.to().getParent();
            if (dir != null) Files.createDirectories(dir);
            Files.move(move.from(), move.to());
            moved.add(move);
        }

        void place(Path target) throws IOException {
            Path tmp = Objects.requireNonNull(pending.get(target), "staged");
            boolean existed = Files.exists(target);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            pending.remove(target);
            if (!existed) placedNew.add(target);
        }

        /** Best effort: remove the staged siblings and the new files, and move the moved ones back. */
        void rollBack() {
            for (Path tmp : pending.values()) PathUtil.deleteRecursively(tmp);
            for (Path placed : placedNew) PathUtil.deleteRecursively(placed);
            for (int i = moved.size() - 1; i >= 0; i--) {
                FrontendFiles.Move move = moved.get(i);
                try {
                    Path dir = move.from().getParent();
                    if (dir != null) Files.createDirectories(dir);
                    Files.move(move.to(), move.from());
                } catch (IOException e) {
                    Log.debug("import roll-back: " + move.to() + " stays", e);
                }
            }
        }
    }

    /**
     * Whether the written pins still need checking against their dependencies: Gradle's resolved
     * versions need none, and a dry run wrote nothing to check.
     */
    public static boolean needsPinCheck(Written w, boolean dryRun) {
        return !dryRun && !w.converted().pinsResolved();
    }

    /** The written report with a row per pin {@code raise} lifted, or one naming why the pins were not checked. */
    public static ImportReport checkPins(Written w, Path out, PinRaise raise, ImportProgress progress) {
        try {
            return raisedAt(w.report(), raise, out, w.converted().modules().isEmpty(), progress);
        } catch (IOException e) {
            ImportReport.Builder b = rebuilt(w.report());
            b.warning(notChecked(e));
            return b.build();
        }
    }

    /** Write the report ({@code report}, else a fresh file under {@code tmpDir}) and settle the outcome. */
    public static Outcome finish(
            Written w, ImportReport importReport, Path source, @Nullable Path tmpDir, @Nullable Path report) {
        try {
            Path reportTarget = report;
            if (reportTarget == null && tmpDir != null) {
                var proj = w.converted().root().project();
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
            return new Outcome(0, issues, null, w.wrote(), reportTarget);
        } catch (IOException e) {
            return Outcome.failed(1, e.getMessage());
        }
    }

    /** {@code file} relative to {@code base} when it sits under it, for a progress detail. */
    private static String display(Path base, Path file) {
        Path b = base.toAbsolutePath().normalize();
        Path f = file.toAbsolutePath().normalize();
        return f.startsWith(b) ? b.relativize(f).toString().replace('\\', '/') : f.toString();
    }

    /** A builder holding {@code report}'s rows, to append to. */
    private static ImportReport.Builder rebuilt(ImportReport report) {
        ImportReport.Builder out = ImportReport.builder();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) out.error(issue.message());
            else out.warning(issue.message());
        }
        return out;
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
     * Raises the written project's exact pins that a floors solve needs higher, returning one
     * line per raise; {@code progress} hears its solves. The engine supplies it; the import alone
     * cannot solve.
     */
    @FunctionalInterface
    public interface PinRaise {
        List<String> raise(Path lockDir, ImportProgress progress) throws Exception;

        PinRaise NONE = (lockDir, progress) -> List.of();
    }

    /**
     * {@code report} after appending each of {@code tables} to {@code configFile}, with a row for a
     * table left out because the file already declares a repository of that name.
     */
    static ImportReport machineCredentials(
            ImportReport report,
            List<UserConfigEditor.CredentialTable> tables,
            Path configFile,
            List<Path> wrote,
            Path source)
            throws IOException {
        if (tables.isEmpty()) return report;
        ImportReport.Builder out = rebuilt(report);
        boolean written = false;
        // The machine's config changes on the import's behalf: its entry names the build it came from.
        ChangeActor importer =
                ChangeActor.importOf(source.toAbsolutePath().normalize().toString());
        for (UserConfigEditor.CredentialTable table : tables) {
            if (ChangeActor.as(importer, () -> UserConfigEditor.addCredential(configFile, table))) {
                written = true;
            } else if (!UserConfigEditor.declaresRepositoryAt(configFile, table)) {
                out.warning(table.name() + ": ~/.jk/config.toml has another table by that name; left it");
            }
        }
        if (written) wrote.add(configFile);
        return out.build();
    }

    /**
     * The pin check over the written root manifest. The check reads a directory's {@code jk.toml};
     * a single project written under another name ({@code jk import -t other.toml}) is checked as a
     * {@code jk.toml} in a scratch directory and written back, so it matches what an import in place
     * writes.
     */
    private static ImportReport raisedAt(
            ImportReport report, PinRaise raise, Path out, boolean single, ImportProgress progress) throws IOException {
        Path dir = Objects.requireNonNull(out.toAbsolutePath().getParent());
        if (!single || out.getFileName().toString().equals(ManifestPaths.MANIFEST))
            return raised(report, raise, dir, progress);
        Path scratch = Files.createTempDirectory("jk-import-");
        try {
            Path manifest = Files.copy(out, scratch.resolve(ManifestPaths.MANIFEST));
            ImportReport checked = raised(report, raise, scratch, progress);
            Files.copy(manifest, out, StandardCopyOption.REPLACE_EXISTING);
            return checked;
        } finally {
            PathUtil.deleteRecursively(scratch);
        }
    }

    /** The report row for a pin check that could not run; the cause goes to the log. */
    private static String notChecked(Exception e) {
        Log.debug("import: pins not checked", e);
        return "pins not checked; jk lock reports any set too low";
    }

    /** {@code report} with one warning per raised pin, or one naming why the pins were not checked. */
    private static ImportReport raised(ImportReport report, PinRaise raise, Path lockDir, ImportProgress progress) {
        List<String> lines;
        String failed = null;
        try {
            lines = raise.raise(lockDir, progress);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            lines = List.of();
            failed = notChecked(e);
        }
        if (lines.isEmpty() && failed == null) return report;
        ImportReport.Builder out = rebuilt(report);
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
        return (existing.size() == 1 ? "" : existing.size() + " manifests: ") + String.join(", ", existing)
                + " already " + (existing.size() == 1 ? "exists" : "exist") + "; nothing written, pass "
                + OVERWRITE_FLAG + " to replace " + (existing.size() == 1 ? "it" : "them");
    }
}
