// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.PluginConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link TaskSpec.Body} gets to work with, inside the plugin's worker JVM: the resolved
 * declared inputs, the scratch output root, progress labelling, and JDK tool forks — and nothing
 * about action keys, the CAS, or jk's directory layout.
 */
public interface TaskExec {

    /** The module's compiled classes dir (resources copied in) — {@link In#classes()}. */
    Path classesDir();

    /** The resolved production RUNTIME classpath — {@link In#runtimeClasspath()}. */
    List<Path> runtimeClasspath();

    /**
     * Lock-ordered production RUNTIME entries with real file names and container dirs —
     * {@link In#runtimeEntries()}. Empty unless declared.
     */
    List<PackageIo.RuntimeEntry> runtimeEntries();

    /** The plugin's validated config table. */
    PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** The module's project dir — working directory for tool forks that read project files. */
    Path moduleDir();

    /** The scratch root the step's declared output dirs resolve under. */
    Path scratch();

    /**
     * An engine-supplied extra: a manifest-contributed {@code step-dependency} artifact, by its
     * artifact id — the fetched jar/binary's path. The author declares the coordinate in the
     * manifest and never learns where jk caches it.
     */
    Optional<Path> extra(String name);

    /** A chained step's output root ({@link In#stepOutput} input); empty when it did not run. */
    Optional<Path> stepOutput(String step);

    /** As {@link #stepOutput} but required — a declared {@code In.stepOutput} is never absent. */
    default Path requireStepOutput(String step) {
        return stepOutput(step)
                .orElseThrow(() -> new IllegalStateException(
                        "step output not provided: " + step + " — declare it with In.stepOutput(...)"));
    }

    /** As {@link #extra}, throwing with the missing artifact id (for required tools). */
    default Path requireExtra(String name) {
        return extra(name)
                .orElseThrow(() -> new IllegalStateException("step-dependency `" + name
                        + "` was not supplied — declare it under [[contribute.step-dependency]]; one that"
                        + " carries for-step reaches only the steps and packagers it names"));
    }

    /** Resolve (and create) a declared output dir under {@link #scratch()}. */
    default Path outputDir(String rel) throws IOException {
        return Files.createDirectories(scratch().resolve(rel));
    }

    /** The JDK this build runs on — for {@link #tool} forks. */
    Path javaHome();

    /**
     * Whether this job forbids network access — the user's {@code --offline}, decided once by the
     * engine and stamped onto the spec at the fork. A worker that is about to reach out asks this
     * first and refuses, naming what it wanted; it must never consult {@code JK_OFFLINE} or a
     * system property, which inside a forked JVM describe the engine daemon's startup environment
     * rather than this job.
     */
    boolean offline();

    /** Progress label surfaced in the build UI. */
    void label(String text);

    /** A {@code bin/<name>} fork off {@link #javaHome()} ({@code java}, {@code javac}, …). */
    default ToolRun tool(String bin) {
        return new ToolRun(javaHome(), bin);
    }

    /** A fork of an arbitrary executable (a fetched native tool: aapt2, protoc, …). */
    default ToolRun tool(Path executable) {
        return new ToolRun(executable);
    }

    /** Convenience for the common case. */
    default ToolRun java() {
        return tool("java");
    }

    /**
     * One tool subprocess: build args, run, get exit + combined output. <strong>The one process
     * fork in the plugin family</strong> — {@link #start()} owns the only {@code ProcessBuilder} a
     * plugin needs, so a plugin never hand-rolls argv assembly, the Windows {@code .exe} shape or
     * the stderr merge. Obtain one from your exec surface ({@link TaskExec#tool}, {@link
     * PackageIo#tool}, {@link PluginCommandExec#tool}); the constructors are public because a
     * plugin's own static helper is handed a {@code javaHome} or an executable path and has no exec
     * surface to ask — an owner a caller cannot reach is not an owner.
     */
    final class ToolRun {
        private final String executable;
        private final List<String> args = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private @Nullable Path cwd;

        public ToolRun(Path javaHome, String bin) {
            this.executable = JdkFingerprint.tool(javaHome, bin).toString();
        }

        public ToolRun(Path executable) {
            this.executable = executable.toAbsolutePath().toString();
        }

        public ToolRun classpath(List<Path> entries) {
            args.add("-cp");
            args.add(Classpaths.join(entries));
            return this;
        }

        public ToolRun mainClass(String main) {
            args.add(main);
            return this;
        }

        public ToolRun args(List<String> more) {
            args.addAll(more);
            return this;
        }

        public ToolRun arg(String one) {
            args.add(one);
            return this;
        }

        public ToolRun cwd(Path dir) {
            this.cwd = dir;
            return this;
        }

        /** One child-environment entry, added to (not replacing) the inherited environment. */
        public ToolRun env(String name, String value) {
            env.put(name, value);
            return this;
        }

        /** As {@link #env(String, String)} for a whole table, in iteration order. */
        public ToolRun env(Map<String, String> more) {
            env.putAll(more);
            return this;
        }

        /**
         * The command line {@link #start()} forks, resolved head first: the absolute executable, or
         * the {@code javaHome} tool via {@link JdkFingerprint#tool} (which owns the Windows
         * {@code .exe} shape), then the args in call order. The one assembly — every fork below
         * builds its {@code ProcessBuilder} from this list.
         */
        public List<String> command() {
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(args);
            return command;
        }

        /**
         * Start the child and hand it back, stderr merged into stdout on a pipe. {@link #run()}
         * and {@link #stream} are drains over this, and a plugin that needs its own drain — a
         * timeout, an early return on a marker line — takes the {@link Process} from here rather
         * than assembling a second launcher. {@link #start(ProcessBuilder.Redirect)} is the same
         * fork with the output sent elsewhere.
         */
        public Process start() throws IOException {
            return start(ProcessBuilder.Redirect.PIPE);
        }

        /**
         * As {@link #start()}, with the child's combined output sent to {@code output} instead of
         * a pipe: a file ({@link ProcessBuilder.Redirect#appendTo}) for a child that keeps writing
         * after the plugin has returned — an emulator's log — where a pipe would need a reader for
         * the child's whole life and break its next write when that reader went away.
         */
        public Process start(ProcessBuilder.Redirect output) throws IOException {
            ProcessBuilder pb =
                    new ProcessBuilder(command()).redirectErrorStream(true).redirectOutput(output);
            if (cwd != null) pb.directory(cwd.toFile());
            pb.environment().putAll(env);
            return pb.start();
        }

        /** Fork and drain: exit code + combined stdout/stderr. */
        public Result run() throws IOException, InterruptedException {
            StringBuilder output = new StringBuilder();
            int exit = stream(line -> output.append(line).append('\n'));
            return new Result(exit, output.toString());
        }

        /**
         * Fork and drain line by line into {@code sink} (blank lines included, no trailing
         * newline), returning the exit code. For output a plugin reports as it arrives instead of
         * buffering — an {@code adb install}'s progress, a tool's log.
         */
        public int stream(Consumer<String> sink) throws IOException, InterruptedException {
            Process process = start();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                @Nullable String line;
                while ((line = reader.readLine()) != null) sink.accept(line);
            }
            return process.waitFor();
        }

        public record Result(int exit, String output) {}
    }
}
