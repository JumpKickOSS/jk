// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.watch.ProcessTrees;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.script.ScriptHeader;
import cc.jumpkick.script.ScriptHeaderParser;
import cc.jumpkick.tool.ToolTarget;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Runs a standalone {@code .java}/{@code .kt}/{@code .kts}/{@code .groovy}/{@code .jar} for {@code jk tool run
 * <file>}. Preparation (resolve/compile) is engine-hosted; the user process execs client-side after
 * the progress widget clears so it owns the TTY.
 */
final class ScriptRunner {

    private final GlobalOptions global;
    private final @Nullable Path cacheDirOverride;
    private final @Nullable URI repoUrl;
    private final boolean forceRecompile;
    private final List<String> extraDeps;
    private final List<String> extraJavaOptions;

    ScriptRunner(GlobalOptions global, @Nullable Path cacheDirOverride, @Nullable URI repoUrl, boolean forceRecompile) {
        this(global, cacheDirOverride, repoUrl, forceRecompile, List.of(), List.of());
    }

    /** As above with {@code --with}/alias dep injections and extra JVM options for the exec. */
    ScriptRunner(
            GlobalOptions global,
            @Nullable Path cacheDirOverride,
            @Nullable URI repoUrl,
            boolean forceRecompile,
            List<String> extraDeps,
            List<String> extraJavaOptions) {
        this.global = global;
        this.cacheDirOverride = cacheDirOverride;
        this.repoUrl = repoUrl;
        this.forceRecompile = forceRecompile;
        this.extraDeps = extraDeps;
        this.extraJavaOptions = extraJavaOptions;
    }

    /**
     * Run {@code file} (dispatched by extension) with {@code args} forwarded to the program. The
     * caller guarantees the target classified as {@link cc.jumpkick.tool.ToolTarget.RunnableFile}.
     */
    int run(Path file, List<String> args) throws IOException, InterruptedException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar")) return runJar(file, args);
        String mode = ToolTarget.scriptMode(name);
        if (mode == null) throw new IllegalArgumentException("unsupported file type: " + file);
        return runScript(mode, file, args);
    }

    /**
     * A {@code .java}, {@code .kt}, {@code .kts} or {@code .groovy} script: the engine compiles it once
     * into jk's cache, then a plain {@code java} runs its classes. The Kotlin stdlib rides along when
     * the prepare names one.
     */
    private int runScript(String mode, Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CommandWedge.printFail("Tool", "script not found: " + script);
            return Exit.NO_INPUT;
        }
        ScriptHeader header = readHeader(script);
        EngineRequests.ScriptPrepareOutcome prep = prepare(mode, script);
        if (!prep.result().success()) return failureExitCode(prep.result());
        List<Path> runtime = new ArrayList<>(prep.classpath());
        if (prep.stdlib() != null) runtime.add(prep.stdlib());
        return execJava(
                prep.classesDir(),
                runtime,
                header.javaOptions(),
                Objects.requireNonNull(prep.mainClass(), "script-prepare terminal carried no main class"),
                args);
    }

    // --- .jar ------------------------------------------------------------

    private int runJar(Path jar, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(jar)) {
            CommandWedge.printFail("Tool", "jar not found: " + jar);
            return Exit.NO_INPUT;
        }
        EngineRequests.ScriptPrepareOutcome prep = prepare("jar", jar);
        if (!prep.result().success()
                || Objects.requireNonNull(prep.mainClass(), "script-prepare terminal carried no main class") == null) {
            for (BuildPlanResult.Diagnostic d : prep.result().errors()) {
                if ("no-main-class".equals(d.code())) return Exit.DATA_ERR;
            }
            return failureExitCode(prep.result());
        }

        List<Path> classpath = prep.classpath();
        Path java = JdkFingerprint.java(JavaHomes.runningJavaHome());
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(extraJavaOptions);
        if (classpath.size() == 1) {
            // No extra deps — `java -jar` is cleaner and honors the jar's Class-Path attribute.
            command.add("-jar");
            command.add(WorkingDirectory.resolve(jar).toString());
        } else {
            command.add("-cp");
            command.add(Classpaths.join(classpath));
            command.add(Objects.requireNonNull(prep.mainClass(), "script-prepare terminal carried no main class"));
        }
        command.addAll(args);
        Process p = CliOutput.handOffTerminal(processFor(command));
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return ProcessTrees.awaitExit(p);
    }

    // --- shared helpers --------------------------------------------------

    /**
     * Run one mode's preparation plan — engine-hosted normally, in-process through the {@link
     * standard single-plan progress either way.
     */
    private EngineRequests.ScriptPrepareOutcome prepare(String mode, Path file)
            throws IOException, InterruptedException {
        BuildPlanConsole.Mode consoleMode = BuildPlanConsole.modeFor(global);

        return EngineClient.runScriptPrepare(
                EnginePaths.current(),
                new EngineRequests.ScriptPrepareRequest(
                        mode, WorkingDirectory.resolve(file), cacheDir(), repoUrl, forceRecompile, extraDeps),
                steps -> BuildPlanConsole.chooseConsoleListener("script", steps, consoleMode));
    }

    /** Client-side header parse — only the exec-relevant bits ({@code //JAVA_OPTIONS}) are read here. */
    private static ScriptHeader readHeader(Path script) throws IOException {
        return ScriptHeaderParser.parse(new String(Files.readAllBytes(script), StandardCharsets.UTF_8));
    }

    /**
     * Map a failed plan to exit code 1. The listener already painted the diagnostic and the "Failed"
     * bar; we don't repeat ourselves.
     */
    private int failureExitCode(BuildPlanResult result) {
        return 1;
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }

    private int execJava(
            @Nullable Path classesDir, List<Path> classpath, List<String> jvmArgs, String mainClass, List<String> args)
            throws IOException, InterruptedException {
        Path java = JdkFingerprint.java(JavaHomes.runningJavaHome());
        List<Path> full = new ArrayList<>();
        if (classesDir != null) full.add(classesDir);
        full.addAll(classpath);

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(jvmArgs);
        command.addAll(extraJavaOptions);
        command.add("-cp");
        command.add(Classpaths.join(full));
        command.add(mainClass);
        command.addAll(args);
        Process p = CliOutput.handOffTerminal(processFor(command));
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return ProcessTrees.awaitExit(p);
    }

    /** {@code command} with the {@code .env} of the directory jk runs in beneath this environment. */
    private ProcessBuilder processFor(List<String> command) {
        ProcessBuilder pb =
                new ProcessBuilder(command).directory(WorkingDirectory.current().toFile());
        EnvLookup.layerUnder(pb.environment(), EnvLookup.dotEnv(global.workingDir()));
        return pb;
    }
}
