// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.JavadocMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forks the JDK's {@code javadoc} launcher over a module's Java sources. Options travel in an
 * {@code @argfile}; output is parsed into located {@code file:line: warning|error: message} lines,
 * deduplicated because javadoc repeats a comment's problems once per index pass.
 */
public final class JavadocTool {

    private static final Pattern LOCATED = Pattern.compile("^(.+?\\.java):(\\d+): (warning|error): (.*)$");
    private static final long TIMEOUT_MINUTES = 30;

    private JavadocTool() {}

    /** Exit status, located warnings and errors in first-seen order, and the whole output. */
    public record Result(int exit, List<String> warnings, List<String> errors, String output) {
        public boolean success() {
            return exit == 0;
        }
    }

    /**
     * The options a run keys on: lenient turns doclint off, strict keeps javadoc's own checks. No
     * timestamps and no status chatter, so the output tree — and the jar — is a pure function of
     * the inputs.
     */
    public static List<String> options(JavadocMode mode, int release) {
        List<String> opts = new ArrayList<>(List.of("-quiet", "-notimestamp", "-encoding", "UTF-8"));
        if (mode != JavadocMode.STRICT) opts.add("-Xdoclint:none");
        if (release > 0) {
            opts.add("--release");
            opts.add(Integer.toString(release));
        }
        return List.copyOf(opts);
    }

    /** {@code bin/javadoc} of the project JDK, or of jk's own runtime when that home ships none (a JRE). */
    public static Path executable(Path javaHome) {
        Path tool = JdkFingerprint.tool(javaHome, "javadoc");
        return Files.isRegularFile(tool) ? tool : JdkFingerprint.tool(JavaHomes.runningJavaHome(), "javadoc");
    }

    /** Document {@code sources} into {@code outDir}, which must exist and be empty. */
    public static Result run(
            Path javaHome, Path outDir, List<Path> sources, List<Path> classpath, List<String> options, Path workdir)
            throws IOException, InterruptedException {
        Path argfile = Files.createTempFile(outDir.getParent(), "javadoc-", ".args");
        try {
            Files.write(argfile, argfileLines(outDir, sources, classpath, options), StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(executable(javaHome).toString(), "@" + argfile.toAbsolutePath())
                    .directory(workdir.toFile())
                    .redirectErrorStream(true);
            Process proc = JobWorkers.start(pb);
            byte[] captured;
            try (var in = proc.getInputStream()) {
                captured = in.readAllBytes();
            }
            if (!proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                throw new IOException("javadoc timed out after " + TIMEOUT_MINUTES + " minutes");
            }
            return parse(proc.exitValue(), new String(captured, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(argfile);
        }
    }

    /** Split {@code output} into deduplicated located warnings and errors. */
    static Result parse(int exit, String output) {
        Set<String> warnings = new LinkedHashSet<>();
        Set<String> errors = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            Matcher m = LOCATED.matcher(line);
            if (!m.matches()) continue;
            if ("error".equals(m.group(3))) errors.add(line);
            else warnings.add(line);
        }
        return new Result(exit, List.copyOf(warnings), List.copyOf(errors), output);
    }

    static List<String> argfileLines(Path outDir, List<Path> sources, List<Path> classpath, List<String> options) {
        List<String> lines = new ArrayList<>();
        lines.add("-d");
        lines.add(quote(outDir.toAbsolutePath().toString()));
        for (String opt : options) lines.add(quote(opt));
        if (!classpath.isEmpty()) {
            lines.add("-classpath");
            List<Path> absolute = new ArrayList<>();
            for (Path p : classpath) absolute.add(p.toAbsolutePath());
            lines.add(quote(Classpaths.join(absolute)));
        }
        for (Path src : sources) lines.add(quote(src.toAbsolutePath().toString()));
        return lines;
    }

    /** javadoc's argfile syntax: double quotes, with backslashes and quotes escaped. */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
