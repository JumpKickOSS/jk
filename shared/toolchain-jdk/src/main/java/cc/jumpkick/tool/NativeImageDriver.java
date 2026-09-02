// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
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

    /** CreateProcess caps a command line near 8191 chars; leave room for the quoting we only estimate. */
    private static final int ARG_FILE_THRESHOLD = 8000;

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
     *
     * <p>On Windows an over-long command line is handed over as an {@code @argfile} and a multi-entry
     * classpath as a pathing jar; both are temp files in the output directory, removed on the way
     * out. A killed JVM leaves them behind.
     */
    public static int run(Request request, ProgressListener listener, Consumer<String> out)
            throws IOException, InterruptedException {
        Path binary = resolve(request.javaHome()).orElseThrow(() -> notFoundError(request.javaHome()));
        Files.createDirectories(request.outputPath().toAbsolutePath().getParent());

        // Do NOT use inheritIO() — it writes directly to fd 1/2, escaping the view
        // layer entirely. Instead each stream is drained line-by-line into the
        // caller's sink (the engine's TaskContext::output), which renders output
        // above the TUI progress bar. No System.out/err, no reliance on a stream swap.
        Consumer<String> sink = (out == null) ? line -> {} : out;
        Path argFile = null;
        Path pathingJar = null;
        try {
            Request effective = request;
            // Windows: Graal's launcher re-execs java with our -cp, so the classpath also lands in
            // a CreateProcess (~8191 chars) we never assemble and an outer @argfile cannot shorten.
            // That inner command line is not measurable from here, which is why this is not
            // length-gated the way needsArgFile is: any multi-entry classpath collapses.
            if (Os.isWindows() && !request.verbatim() && request.classpath().size() > 1) {
                pathingJar = writePathingJar(tempDir(request), request.classpath());
                if (pathingJar != null) {
                    effective = new Request(
                            request.javaHome(),
                            List.of(pathingJar),
                            request.mainClass(),
                            request.outputPath(),
                            request.extraArgs(),
                            request.shared(),
                            request.workingDir(),
                            request.verbatim());
                }
            }
            List<String> command = buildCommand(binary, effective);
            if (needsArgFile(command)) {
                argFile = Files.createTempFile(tempDir(request), "ni-args-", ".txt");
                command = withArgFile(binary, command, argFile);
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            if (effective.workingDir() != null)
                pb.directory(effective.workingDir().toFile());
            Process process = pb.start();
            Thread fwdOut = forwardStdout(process.getInputStream(), listener, sink);
            Thread fwdErr = forwardStream(process.getErrorStream(), sink);
            int exit = process.waitFor();
            fwdOut.join();
            fwdErr.join();
            return exit;
        } finally {
            if (argFile != null) Files.deleteIfExists(argFile);
            if (pathingJar != null) Files.deleteIfExists(pathingJar);
        }
    }

    /**
     * Where the {@code @argfile} and pathing jar are written: the image's own output directory, so a
     * relative {@code Class-Path} entry is short and nothing leaks into a shared temp dir.
     */
    private static Path tempDir(Request request) {
        Path parent = request.outputPath().toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(".");
    }

    /**
     * Windows only, and only once the command approaches the CreateProcess cap — a short invocation
     * keeps a command line the user can copy out of a log and re-run.
     */
    static boolean needsArgFile(List<String> command) {
        return Os.isWindows() && commandLineChars(command) > ARG_FILE_THRESHOLD;
    }

    /** Rough CreateProcess command-line length (quoted the way the JVM typically does). */
    static int commandLineChars(List<String> command) {
        int n = 0;
        for (String a : command) {
            n += a.length() + 3; // space + possible quotes
        }
        return n;
    }

    /**
     * Rewrite {@code [binary, arg…]} to {@code [binary, @argFile]} after writing each arg on its own
     * line (Java/Graal argfile form).
     */
    static List<String> withArgFile(Path binary, List<String> command, Path argFile) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < command.size(); i++) {
            String a = command.get(i);
            // Quote when the token has whitespace or is empty — matches javac @argfile rules.
            if (a.isEmpty() || a.indexOf(' ') >= 0 || a.indexOf('\t') >= 0) {
                body.append('"')
                        .append(a.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            } else {
                body.append(a);
            }
            body.append('\n');
        }
        Files.writeString(argFile, body.toString(), StandardCharsets.UTF_8);
        return List.of(binary.toString(), "@" + argFile.toAbsolutePath());
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
        command.add(Classpaths.join(request.classpath()));
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
    private static Thread forwardStdout(InputStream in, ProgressListener listener, Consumer<String> sink) {
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

    private static Thread forwardStream(InputStream in, Consumer<String> sink) {
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
     * Locate the {@code native-image} binary, searching in order:
     *
     * <ol>
     *   <li>{@code javaHome} — the project-pinned JDK
     *   <li>{@code $GRAALVM_HOME}
     *   <li>every {@code $PATH} entry
     * </ol>
     *
     * <p>Which paths a home is searched at is {@link GraalLauncher}'s answer, not this class's: this
     * method owns the search POLICY (which homes, in what order) and {@code GraalLauncher} owns the
     * LAYOUT ({@code bin} vs {@code lib/svm/bin}, and the three filenames). Returns the first
     * candidate that exists as a regular file.
     */
    public static Optional<Path> resolve(Path javaHome) {
        return resolve(javaHome, BuildEnv.ambient());
    }

    /**
     * As {@link #resolve(Path)} against a caller-supplied environment.
     *
     * <p>Both tiers below read the environment, and inside the engine {@link System#getenv} is the
     * daemon's — whichever shell started it, possibly days ago. A caller on a build path passes
     * {@code BuildEnv.forModule(dir)} so {@code GRAALVM_HOME} and {@code PATH} are the ones the
     * user actually invoked jk with.
     */
    public static Optional<Path> resolve(Path javaHome, UnaryOperator<String> env) {
        // 1. Project-pinned JDK
        Optional<Path> pinned = GraalLauncher.in(javaHome);
        if (pinned.isPresent()) return pinned;

        // 2. $GRAALVM_HOME
        String graalHome = env.apply("GRAALVM_HOME");
        if (graalHome != null && !graalHome.isBlank()) {
            Optional<Path> fromEnv = GraalLauncher.in(Path.of(graalHome));
            if (fromEnv.isPresent()) return fromEnv;
        }

        // 3. $PATH — where the launcher sits under an entry is GraalLauncher's business, not ours.
        for (String dir : SearchPath.entries(env.apply("PATH"))) {
            if (dir.isBlank()) continue;
            Optional<Path> onPath = GraalLauncher.onPathEntry(Path.of(dir));
            if (onPath.isPresent()) return onPath;
        }

        return Optional.empty();
    }

    public static IOException notFoundError(Path javaHome) {
        return new IOException(GraalLauncher.NAME + " binary not found.\n"
                + "  Checked ("
                + GraalLauncher.searchedDirs()
                + "): "
                + (javaHome != null ? javaHome + ", " : "")
                + "$GRAALVM_HOME, PATH\n"
                + "  Install a GraalVM JDK and pin it:\n"
                + "    jk jdk install graalvm-25\n"
                + "    (or set $GRAALVM_HOME to your GraalVM installation)");
    }

    /**
     * Empty jar whose manifest {@code Class-Path} lists {@code classpath} relative to the jar's own
     * directory, forward-slashed. Graal's driver resolves each entry as a plain filesystem path — not
     * a {@code file:} URL. Keeps {@code -cp} to one short token so Windows CreateProcess stays under
     * the length cap.
     *
     * <p>{@code null} when any entry contains a space: {@code Class-Path} is space-separated and no
     * escaping survives a consumer that does not URL-decode. The caller then passes the full {@code
     * -cp} — a command line that may be too long fails loudly, an entry that silently does not
     * resolve does not.
     */
    static Path writePathingJar(Path dir, List<Path> classpath) throws IOException {
        Path jarDir = dir.toAbsolutePath().normalize();
        StringBuilder cp = new StringBuilder();
        for (Path p : classpath) {
            Path abs = p.toAbsolutePath().normalize();
            String entry;
            try {
                entry = jarDir.relativize(abs).toString().replace('\\', '/');
            } catch (IllegalArgumentException differentRoot) {
                entry = abs.toString().replace('\\', '/');
            }
            if (entry.indexOf(' ') >= 0) return null;
            if (!cp.isEmpty()) cp.append(' ');
            cp.append(entry);
        }
        Files.createDirectories(dir);
        Path jar = Files.createTempFile(dir, "ni-cp-", ".jar");
        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Class-Path", cp.toString());
        // A manifest-only classpath jar: tiny, but it goes through the one owner like every
        // other archive so the buffering question is not re-decided here.
        try (OutputStream out = DeterministicZip.archiveStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            // manifest-only
        }
        return jar;
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
