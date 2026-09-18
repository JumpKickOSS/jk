// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.HostedEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk import <file>} — convert a Maven or Gradle build to {@code jk.toml} (engine-hosted
 * converter; a Gradle build's model comes from a Gradle fork). This command pre-flights
 * sources/overwrite and renders progress.
 */
public final class ImportCommand implements CliCommand {

    private static final List<String> AUTO_DETECT_ORDER =
            List.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "pom.xml");

    /** The Gradle scripts an import takes: a project's build script, or the settings file of a build root. */
    private static final List<String> GRADLE_SOURCES =
            List.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle");

    @Override
    public String name() {
        return "import";
    }

    @Override
    public String description() {
        return "Convert a Maven or Gradle build to jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<file>", "Path to write jk.toml.", "--out"),
                Opt.value("<file>", "Path to write the import report.", "--report"),
                Opt.flag("Overwrite existing jk.toml.", "--overwrite"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("file", Arity.ZERO_OR_ONE, "The build file to import (auto-detected if omitted)."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        Path source =
                in.positionals().isEmpty() ? null : Path.of(in.positionals().get(0));
        boolean force = in.isSet("overwrite");
        GlobalOptions global = GlobalOptions.from(in);
        Path baseDir = global.workingDir();
        // Every path the command line names is the user's, so a relative one is read against the
        // directory the command runs in before it crosses to the engine, whose own directory is not
        // the user's.
        Path out = in.value("out").map(o -> baseDir.resolve(o).normalize()).orElse(null);
        Path reportPath =
                in.value("report").map(r -> baseDir.resolve(r).normalize()).orElse(null);

        if (source == null) {
            source = autoDetectSource(baseDir);
            if (source == null) {
                CommandWedge.printFail(
                        "Import",
                        "no build file found in " + baseDir + " (looked for " + String.join(", ", AUTO_DETECT_ORDER)
                                + ").");
                return Exit.NO_INPUT;
            }
            CliOutput.out("Importing " + PathDisplay.styled(source, baseDir));
        } else {
            source = baseDir.resolve(source).normalize();
            if (!Files.exists(source)) {
                CommandWedge.printFail("Import", "source not found: " + PathDisplay.styled(source, baseDir));
                return Exit.NO_INPUT;
            }
        }

        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith("pom.xml") && !GRADLE_SOURCES.contains(filename)) {
            CommandWedge.printFail("Import", "expected pom.xml, build.gradle(.kts) or settings.gradle(.kts)");
            return Exit.USAGE;
        }

        Path projectDir = Objects.requireNonNull(source.toAbsolutePath().getParent(), "project dir");
        Path target = out != null ? out : projectDir.resolve(ManifestPaths.MANIFEST);
        if (Files.exists(target) && !force) {
            CommandWedge.printFail(
                    "Import", "refusing to overwrite " + PathDisplay.styled(target, baseDir) + " (pass --overwrite).");
            return Exit.CANT_CREATE;
        }

        Path cache = JkDirs.cache();

        // Renders the worker's progress notes as they stream — identical for both transports.
        HostedEvents.NoteObserver observer = (kind, text) -> {
            if ("wrote".equals(kind)) {
                // Settled-path chrome: one wedge per wrote note (engine may emit several).
                CommandWedge.printOk("Import", "Wrote " + text);
            } else {
                CliOutput.out(text);
            }
        };

        int exit;
        int warnings;
        String error;
        EngineRequests.ImportOutcome outcome;
        try {
            outcome = EngineClient.runImport(
                    EnginePaths.current(),
                    new EngineRequests.ImportRequest(
                            source.toAbsolutePath(),
                            target.toAbsolutePath(),
                            projectDir,
                            JkDirs.tmp(),
                            force,
                            reportPath,
                            cache),
                    steps -> new BuildPlanListener() {},
                    observer);
        } catch (IOException e) {
            CommandWedge.printFail("Import", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!outcome.result().success()) {
            for (BuildPlanResult.Diagnostic d : outcome.result().errors()) {
                CommandWedge.printFail("Import", d.message());
            }
            return 1;
        }
        exit = outcome.exitCode();
        warnings = outcome.warnings();
        error = outcome.error();

        if (error != null) CommandWedge.printFail("Import", error);
        if (warnings != 0) CliOutput.out("Import notes: " + warnings + " issue(s)");
        return exit;
    }

    /**
     * Pick {@code <tmpDir>/<coord>-<n>-<sourceFile>-import.md}, incrementing {@code n} past any
     * existing file.
     */
    static Path defaultReportPath(Path tmpDir, String coord, String sourceFileName) {
        for (int n = 1; ; n++) {
            Path candidate = tmpDir.resolve(coord + "-" + n + "-" + sourceFileName + "-import.md");
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private static @Nullable Path autoDetectSource(Path dir) {
        for (String name : AUTO_DETECT_ORDER) {
            Path c = dir.resolve(name);
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }
}
