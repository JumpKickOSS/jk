// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportProgress;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.mvn.PomImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A Gradle build → jk manifests. A build with a {@code settings.gradle(.kts)} is read through
 * Gradle itself ({@link ModelSource}: the provisioned distribution evaluates the build in a fork
 * and writes its project model), so every project, configuration and registered task imports;
 * when Gradle cannot run, and for a lone build script, the {@link GradleImporter} scanner reads
 * the declarative idioms of the one file and the report says which path was taken.
 */
public final class GradleBuildImport {

    /**
     * The root manifest, the members keyed by root-relative path (empty for one project), the rows,
     * the {@code ~/.jk/config.toml} tables carrying the credentials the build reads from the
     * environment, and whether the pins are the versions Gradle resolved — true when Gradle read the
     * build, false for the scanner, whose pins are only what the script declares.
     */
    public record Result(
            JkBuild root,
            Map<String, JkBuild> modules,
            ImportReport report,
            List<UserConfigEditor.RepositoryTable> machineRepositories,
            boolean pinsResolved) {}

    /** Reads the evaluated project model of the build rooted at {@code buildRoot}, as the init script's JSON. */
    public interface ModelSource {
        String read(Path buildRoot, List<String> pluginIds, ImportProgress progress) throws IOException;
    }

    /** The build-script names a Gradle project directory carries, Kotlin DSL first. */
    public static final List<String> BUILD_FILES = List.of("build.gradle.kts", "build.gradle");

    /** Every file name {@code jk import} takes as a Gradle source, the build script preferred to settings. */
    public static final List<String> SOURCE_FILES =
            List.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle");

    private final @Nullable ModelSource model;

    private GradleBuildImport(@Nullable ModelSource model) {
        this.model = model;
    }

    /** Reads every build through the scanner alone — offline tests and hosts without a fork. */
    public static GradleBuildImport scannerOnly() {
        return new GradleBuildImport(null);
    }

    /** Reads a build with a settings file through {@code model}, the rest through the scanner. */
    public static GradleBuildImport withModel(ModelSource model) {
        return new GradleBuildImport(Objects.requireNonNull(model, "model"));
    }

    /** Whether {@code filename} is a Gradle build or settings script. */
    public static boolean isGradleSource(String filename) {
        return SOURCE_FILES.contains(filename.toLowerCase(Locale.ROOT));
    }

    public static boolean isSettingsFile(String filename) {
        return GradleImporter.SETTINGS_FILES.contains(filename.toLowerCase(Locale.ROOT));
    }

    /** The settings file in {@code dir}, or {@code null}. */
    static @Nullable Path settingsIn(Path dir) {
        for (String name : GradleImporter.SETTINGS_FILES) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    static @Nullable Path buildScriptIn(Path dir) {
        for (String name : BUILD_FILES) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Import the build {@code source} belongs to. {@code progress} hears the model read: a milestone
     * per stage (provisioning, evaluating) and the projects Gradle has configured and described. A model read that exceeds the resolve
     * budget fails the import, as a Maven import's POM reads do; any other reason Gradle could not
     * evaluate the build falls back to the scanner with a row saying so.
     */
    public Result importBuild(Path source, ImportProgress progress) throws IOException {
        Path dir = Objects.requireNonNull(source.toAbsolutePath().getParent(), "project dir");
        boolean settingsSource = isSettingsFile(source.getFileName().toString());
        Path settings = settingsIn(dir);
        String modelFailure = null;
        if (settings != null && model != null) {
            RefreshVersions refreshVersions = RefreshVersions.beside(dir);
            try {
                String json = model.read(dir, GradleModelImporter.probedPluginIds(), progress);
                return GradleModelImporter.importModel(json, dir, refreshVersions);
            } catch (IOException e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (message.startsWith(PomImporter.BUDGET_EXCEEDED)) throw e;
                modelFailure = message;
            }
        }
        Path script = settingsSource ? buildScriptIn(dir) : source;
        progress.detail("scanning " + (script == null ? source.getFileName() : script.getFileName()));
        if (script == null) {
            throw new IOException(source.getFileName() + " has no build script beside it, so only Gradle can read it"
                    + (modelFailure == null ? "" : ", and Gradle failed: " + modelFailure));
        }
        GradleImporter.Result scanned = GradleImporter.importFrom(script);
        ImportReport.Builder report = ImportReport.builder();
        if (modelFailure != null) {
            report.error("Gradle failed (" + modelFailure + "); read " + script.getFileName()
                    + " only, so computed subprojects, configurations and tasks are missing");
        } else if (settings == null) {
            Path parent = dir.getParent();
            Path parentSettings = parent == null ? null : settingsIn(parent);
            if (parentSettings != null) {
                report.warning(script.getFileName() + " is one project of " + parent + "; import "
                        + parentSettings.getFileName() + " there for every module");
            }
        }
        for (ImportReport.Issue issue : scanned.report().issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) report.error(issue.message());
            else report.warning(issue.message());
        }
        return new Result(scanned.jkBuild(), Map.of(), report.build(), List.of(), false);
    }
}
