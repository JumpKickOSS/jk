// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.script.ScriptHeader;
import cc.jumpkick.script.ScriptHeaderParser;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a standalone {@code .java}/{@code .kt}/{@code .kts}/{@code .jar} for {@code jk tool run
 * <file>}. Preparation (resolve/compile) is engine-hosted; the user process execs client-side after
 * the progress widget clears so it owns the TTY.
 */
final class ScriptRunner {

    private final GlobalOptions global;
    private final Path cacheDirOverride;
    private final Path stateDirOverride;
    private final URI repoUrl;
    private final boolean forceRecompile;
    private final List<String> extraDeps;
    private final List<String> extraJavaOptions;

    ScriptRunner(
            GlobalOptions global, Path cacheDirOverride, Path stateDirOverride, URI repoUrl, boolean forceRecompile) {
        this(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile, List.of(), List.of());
    }

    /** As above with {@code --with}/alias dep injections and extra JVM options for the exec. */
    ScriptRunner(
            GlobalOptions global,
            Path cacheDirOverride,
            Path stateDirOverride,
            URI repoUrl,
            boolean forceRecompile,
            List<String> extraDeps,
            List<String> extraJavaOptions) {
        this.global = global;
        this.cacheDirOverride = cacheDirOverride;
        this.stateDirOverride = stateDirOverride;
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
        if (name.endsWith(".java")) return runJavaScript(file, args);
        if (name.endsWith(".kts")) return runKtsScript(file, args);
        if (name.endsWith(".kt")) return runKotlinScript(file, args);
        if (name.endsWith(".jar")) return runJar(file, args);
        throw new IllegalArgumentException("unsupported file type: " + file);
    }

    // --- .java -----------------------------------------------------------

    private int runJavaScript(Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CommandWedge.printFail("Tool", "script not found: " + script);
            return Exit.NO_INPUT;
        }
        ScriptHeader header = readHeader(script);
        EngineRequests.ScriptPrepareOutcome prep = prepare("java", script);
        if (!prep.result().success()) return failureExitCode(prep.result());
        return execJava(prep.classesDir(), prep.classpath(), header.javaOptions(), prep.mainClass(), args);
    }

    // --- .kt -------------------------------------------------------------

    private int runKotlinScript(Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CommandWedge.printFail("Tool", "script not found: " + script);
            return Exit.NO_INPUT;
        }
        ScriptHeader header = readHeader(script);
        EngineRequests.ScriptPrepareOutcome prep = prepare("kt", script);
        if (!prep.result().success()) return failureExitCode(prep.result());

        // At runtime, the Kotlin stdlib must be on the classpath.
        List<Path> runtime = new ArrayList<>(prep.classpath());
        if (prep.stdlib() != null) runtime.add(prep.stdlib());

        return execJava(prep.classesDir(), runtime, header.javaOptions(), prep.mainClass(), args);
    }

    // --- .kts ------------------------------------------------------------

    private int runKtsScript(Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CommandWedge.printFail("Tool", "script not found: " + script);
            return Exit.NO_INPUT;
        }
        EngineRequests.ScriptPrepareOutcome prep = prepare("kts", script);
        if (!prep.result().success() || prep.kotlincBin() == null) {
            // "kotlinc-missing" is an EX_SOFTWARE (70) shape; everything
            // else collapses to the generic resolver error code.
            for (BuildPlanResult.Diagnostic d : prep.result().errors()) {
                if ("kotlinc-missing".equals(d.code())) return Exit.SOFTWARE;
            }
            return failureExitCode(prep.result());
        }

        // jk resolved any @file:DependsOn/@file:Repository annotations itself (engine-side);
        // plain kotlinc can't compile them (they're main-kts constructs), so exec a
        // line-preserving copy with those annotations commented out.
        String source = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        Path execScript = script.toAbsolutePath();
        String neutralized = ScriptHeaderParser.neutralizeKotlinAnnotations(source);
        if (neutralized != null) {
            Path srcDir = stateDir()
                    .resolve("script-cache")
                    .resolve(cc.jumpkick.host.Hashing.sha256Hex(source.getBytes(StandardCharsets.UTF_8)))
                    .resolve("src");
            Files.createDirectories(srcDir);
            execScript = srcDir.resolve(script.getFileName().toString());
            Files.writeString(execScript, neutralized, StandardCharsets.UTF_8);
        }

        List<String> command = new ArrayList<>();
        command.add(prep.kotlincBin().toString());
        // kotlinc -script runs the script in kotlinc's own JVM; -J passes options to it.
        for (String opt : extraJavaOptions) {
            command.add("-J" + opt);
        }
        command.add("-script");
        // The script's declared deps (@file:DependsOn / //DEPS / //jk dep) — resolved
        // engine-side through jk's CAS, handed to kotlinc instead of main-kts's Ivy.
        if (!prep.classpath().isEmpty()) {
            command.add("-classpath");
            command.add(Classpaths.join(prep.classpath()));
        }
        command.add(execScript.toString());
        if (!args.isEmpty()) {
            command.add("--");
            command.addAll(args);
        }
        Terminals.restoreForChild();
        Process p = new ProcessBuilder(command).inheritIO().start();
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return p.waitFor();
    }

    // --- .jar ------------------------------------------------------------

    private int runJar(Path jar, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(jar)) {
            CommandWedge.printFail("Tool", "jar not found: " + jar);
            return Exit.NO_INPUT;
        }
        EngineRequests.ScriptPrepareOutcome prep = prepare("jar", jar);
        if (!prep.result().success() || prep.mainClass() == null) {
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
            command.add(jar.toAbsolutePath().toString());
        } else {
            command.add("-cp");
            command.add(Classpaths.join(classpath));
            command.add(prep.mainClass());
        }
        command.addAll(args);
        Terminals.restoreForChild();
        Process p = new ProcessBuilder(command).inheritIO().start();
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return p.waitFor();
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
                        mode, file.toAbsolutePath(), cacheDir(), stateDir(), repoUrl, forceRecompile, extraDeps),
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

    private Path stateDir() {
        return stateDirOverride != null ? stateDirOverride : JkDirs.state();
    }

    private int execJava(
            Path classesDir, List<Path> classpath, List<String> jvmArgs, String mainClass, List<String> args)
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
        Terminals.restoreForChild();
        Process p = new ProcessBuilder(command).inheritIO().start();
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return p.waitFor();
    }
}
