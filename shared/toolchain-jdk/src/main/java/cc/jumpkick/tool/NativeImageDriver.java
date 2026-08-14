// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.jdk.HostPlatform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Driver for GraalVM {@code native-image}: verify binary, assemble classpath, exec. Stdout/stderr
 * go to a caller sink (e.g. {@code TaskContext::output}); does not install GraalVM.
 */
public final class NativeImageDriver {

    public record Request(
            Path javaHome,
            List<Path> classpath,
            String mainClass,
            Path outputPath,
            List<String> extraArgs,
            boolean shared,
            Path workingDir,
            boolean verbatim) {

        public Request {
            Objects.requireNonNull(javaHome, "javaHome");
            Objects.requireNonNull(outputPath, "outputPath");
            // An executable needs a main class; a shared library (--shared) has no entry point,
            // and a verbatim command carries its own (Quarkus enters through --features, not main).
            if (!shared && !verbatim) {
                Objects.requireNonNull(mainClass, "mainClass");
            }
            classpath = List.copyOf(classpath);
            extraArgs = List.copyOf(extraArgs);
        }

        public Request(
                Path javaHome,
                List<Path> classpath,
                String mainClass,
                Path outputPath,
                List<String> extraArgs,
                boolean shared) {
            this(javaHome, classpath, mainClass, outputPath, extraArgs, shared, null, false);
        }

        /** An executable image with the given entry point. */
        public Request(Path javaHome, List<Path> classpath, String mainClass, Path outputPath, List<String> extraArgs) {
            this(javaHome, classpath, mainClass, outputPath, extraArgs, false);
        }

        /**
         * Run {@code args} exactly as given from {@code workingDir}. For frameworks that compute
         * their own native-image invocation — Quarkus writes the classpath, the entry feature, the
         * output name and every flag into {@code native-image.args}. jk supplies the toolchain and
         * runs it; rewriting a list the framework derived would only reintroduce guessing.
         *
         * @param outputPath where the produced binary ends up (the args name it themselves, so the
         *     caller moves it there afterwards)
         */
        public static Request verbatim(Path javaHome, Path workingDir, List<String> args, Path outputPath) {
            return new Request(javaHome, List.of(), null, outputPath, args, false, workingDir, true);
        }
    }

    /**
     * Receives structured progress events parsed from native-image's stdout. All callbacks are
     * invoked from the stdout-reader daemon thread.
     */
    public interface ProgressListener {
        /**
         * Called for each {@code [N/M] label} header line in native-image output, including the first.
         * When {@code current == 1} the caller should also grow the scope to account for all upcoming
         * steps.
         *
         * @param current step number (1-based)
         * @param total total steps declared by native-image (e.g. 8)
         * @param label human-readable step description with timing stripped and a trailing ASCII
         *     {@code ...} normalized to the unicode ellipsis {@code …} (e.g. {@code Performing
         *     analysis…})
         */
        void onStep(int current, int total, String label);
    }

    /** {@code [N/M] Some label...} — native-image step header (optional trailing timing columns). */
    private static final Pattern STEP_PATTERN = Pattern.compile("^\\[(\\d+)/(\\d+)]\\s+(.+?)(?:\\s{2,}.*)?$");

    private NativeImageDriver() {}

    /** Exec native-image with no progress listener and a discarded output sink; return its exit code. */
    public static int run(Request request) throws IOException, InterruptedException {
        return run(request, null, null);
    }

    /**
     * Exec native-image; return its exit code.
     *
     * <p>The subprocess's stdout and stderr are forwarded line-by-line to {@code out} (both streams
     * share the sink, preserving the interleaved order the user would see on a console). stdout is
     * additionally parsed for {@code [N/M]} step headers; each header fires {@link
     * ProgressListener#onStep} on the stdout-reader thread. Callers typically buffer lines (for a
     * report file + optional console replay) rather than writing the console live — this driver
     * never touches {@code System.out}/{@code System.err}.
     *
     * <p>{@code listener} may be {@code null} — output still flows to {@code out} but no callbacks are
     * invoked. {@code out} may be {@code null} — output is then discarded. The sink is invoked from
     * the reader daemon threads, so callers must tolerate that.
     */
    public static int run(Request request, ProgressListener listener, Consumer<String> out)
            throws IOException, InterruptedException {
        Path binary = resolve(request.javaHome()).orElseThrow(() -> notFoundError(request.javaHome()));
        List<String> command = buildCommand(binary, request);

        Files.createDirectories(request.outputPath().toAbsolutePath().getParent());

        // Do NOT use inheritIO() — it writes directly to fd 1/2, escaping the view
        // layer entirely. Instead each stream is drained line-by-line into the
        // caller's sink (the engine's TaskContext::output), which renders output
        // above the TUI progress bar. No System.out/err, no reliance on a stream swap.
        Consumer<String> sink = (out == null) ? line -> {} : out;
        ProcessBuilder pb = new ProcessBuilder(command);
        if (request.workingDir() != null) pb.directory(request.workingDir().toFile());
        Process process = pb.start();
        Thread fwdOut = forwardStdout(process.getInputStream(), listener, sink);
        Thread fwdErr = forwardStream(process.getErrorStream(), sink);
        int exit = process.waitFor();
        fwdOut.join();
        fwdErr.join();
        return exit;
    }

    /**
     * Assemble the {@code native-image} command line. Executable builds end with the main class;
     * shared-library builds ({@code --shared}) take no main class and let native-image derive {@code
     * lib<name>.<ext>} + headers from {@code -o}. Package-private for unit testing the assembly
     * without execing.
     */
    static List<String> buildCommand(Path binary, Request request) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        if (request.verbatim()) {
            command.addAll(request.extraArgs());
            return command;
        }
        command.add("-cp");
        command.add(joinClasspath(request.classpath()));
        if (request.shared()) {
            command.add("--shared");
        }
        command.add("-o");
        command.add(request.outputPath().toAbsolutePath().toString());
        command.add("--no-fallback");
        command.addAll(request.extraArgs());
        if (!request.shared()) {
            command.add(request.mainClass());
        }
        return command;
    }

    /**
     * Forward stdout to {@code sink} line-by-line, parsing {@code [N/M]} step headers and firing the
     * listener when found. stderr uses the simpler {@link #forwardStream} (no parsing needed).
     */
    private static Thread forwardStdout(java.io.InputStream in, ProgressListener listener, Consumer<String> sink) {
        Thread t = new Thread(
                () -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sink.accept(line);
                            if (listener != null) {
                                StepHeader step = parseStepHeader(line);
                                if (step != null) {
                                    listener.onStep(step.current(), step.total(), step.label());
                                }
                            }
                        }
                    } catch (IOException ignored) {
                    }
                },
                "native-image-stdout");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Thread forwardStream(java.io.InputStream in, Consumer<String> sink) {
        Thread t = new Thread(
                () -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sink.accept(line);
                        }
                    } catch (IOException ignored) {
                    }
                },
                "native-image-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Locate the {@code native-image} binary, trying in order:
     *
     * <ol>
     *   <li>{@code <javaHome>/bin/native-image} — the project-pinned JDK
     *   <li>{@code $GRAALVM_HOME/bin/native-image} — explicit GraalVM override
     *   <li>{@code native-image} on {@code $PATH}
     * </ol>
     *
     * Returns the first candidate that exists as a regular file.
     */
    public static Optional<Path> resolve(Path javaHome) {
        boolean win = HostPlatform.isWindows();
        String exe = win ? "native-image.cmd" : "native-image";

        // 1. Project-pinned JDK
        if (javaHome != null) {
            Path p = javaHome.resolve("bin").resolve(exe);
            if (Files.isRegularFile(p)) return Optional.of(p);
        }

        // 2. $GRAALVM_HOME
        String graalHome = System.getenv("GRAALVM_HOME");
        if (graalHome != null && !graalHome.isBlank()) {
            Path p = Path.of(graalHome).resolve("bin").resolve(exe);
            if (Files.isRegularFile(p)) return Optional.of(p);
        }

        // 3. $PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String sep = System.getProperty("path.separator", ":");
            for (String dir : pathEnv.split(sep, -1)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir).resolve(exe);
                if (Files.isRegularFile(p)) return Optional.of(p);
            }
        }

        return Optional.empty();
    }

    /** Resolve {@code <javaHome>/bin/native-image} for the current OS (no fallback). */
    public static Path nativeImageBinary(Path javaHome) {
        boolean win = HostPlatform.isWindows();
        return javaHome.resolve("bin").resolve(win ? "native-image.cmd" : "native-image");
    }

    public static IOException notFoundError(Path javaHome) {
        return new IOException("native-image binary not found.\n"
                + "  Checked: "
                + (javaHome != null ? javaHome.resolve("bin/native-image") + ", " : "")
                + "$GRAALVM_HOME/bin/native-image, PATH\n"
                + "  Install a GraalVM JDK and pin it:\n"
                + "    jk jdk install graalvm-25\n"
                + "    (or set $GRAALVM_HOME to your GraalVM installation)");
    }

    private static String joinClasspath(List<Path> classpath) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < classpath.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(classpath.get(i).toAbsolutePath());
        }
        return sb.toString();
    }

    /** Parsed {@code [N/M] label…} header, or {@code null} when the line is not a step header. */
    record StepHeader(int current, int total, String label) {}

    /**
     * Parse a native-image step header. Strips trailing timing columns and rewrites a trailing
     * ASCII {@code ...} to {@code …} so live TUI labels match the rest of the CLI.
     */
    static StepHeader parseStepHeader(String line) {
        if (line == null) return null;
        Matcher m = STEP_PATTERN.matcher(line);
        if (!m.matches()) return null;
        int current = Integer.parseInt(m.group(1));
        int total = Integer.parseInt(m.group(2));
        return new StepHeader(current, total, normalizeStepLabel(m.group(3).trim()));
    }

    /** Package-private for tests: trailing {@code ...} → {@code …}; already-ellipsis left alone. */
    static String normalizeStepLabel(String label) {
        if (label == null || label.isEmpty()) return "";
        if (label.endsWith("...")) {
            return label.substring(0, label.length() - 3) + "…";
        }
        return label;
    }
}
