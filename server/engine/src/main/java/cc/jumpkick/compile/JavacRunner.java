// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.task.ActionKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full {@code javac} subprocess against {@code <java-home>} (pinned JDK, native-image safe, isolated).
 * Always recompiles all handed sources; long classpaths use an {@code @argfile}. Diagnostics parsed
 * from stderr.
 */
public final class JavacRunner {

    private static final Pattern DIAGNOSTIC =
            Pattern.compile("^(?<file>.+?):(?<line>\\d+): (?<sev>error|warning|note): (?<msg>.*)$");

    public CompileResult compile(CompileRequest request) throws IOException {
        Path outDir = request.outputDir();
        Path scratch = null;
        if (outDir == null) {
            scratch = Files.createTempDirectory("jk-check-");
            outDir = scratch;
        } else {
            Files.createDirectories(outDir);
        }

        Path javaHome = request.javaHome() != null ? request.javaHome() : Path.of(System.getProperty("java.home"));
        Path javac = JdkFingerprint.javac(javaHome);
        if (!Files.exists(javac)) {
            throw new IOException(
                    "javac not found at " + javac + " — project.jdk needs to point at a JDK (not a JRE).");
        }

        try {
            Path argfile = writeArgfile(request, outDir);
            try {
                // -J flags reach javac's own JVM launcher and are rejected inside an
                // @argfile (javac processes the file itself, after the JVM is already
                // up) — they must be direct command-line arguments.
                List<String> command = new ArrayList<>();
                command.add(javac.toString());
                // -J flags land on this javac's host JVM (project pin), not the engine's — gate
                // JEP 498 allow by that home's feature so JDK 17/21 pins do not abort at startup.
                command.addAll(JvmOptions.launcherFlags(1, JvmOptions.hostFeature(javaHome)));
                // No PluginAot on bare `javac` — AOT is for `java … PluginMain` workers only
                // (jk-java-compiler ToolProvider host and kotlin-compiler). See PluginAot.
                command.add("@" + argfile);
                ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
                Process process = JobWorkers.start(pb);
                List<String> stray = new ArrayList<>();
                List<CompileResult.Diagnostic> diagnostics;
                int exit;
                try {
                    diagnostics = parseStream(process, stray);
                    exit = process.waitFor();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException("javac was interrupted", e);
                }
                if (exit != 0 && !hasErrors(diagnostics)) {
                    // javac died without any per-source diagnostic (bad flag, unreadable
                    // classpath entry it didn't attribute, a crash, …). Surface whatever it
                    // printed as an ERROR — a failed compile must never be silent.
                    diagnostics = new ArrayList<>(diagnostics);
                    diagnostics.add(new CompileResult.Diagnostic(
                            CompileResult.Severity.ERROR,
                            null,
                            -1,
                            -1,
                            "javac exited with code " + exit
                                    + (stray.isEmpty() ? " and no diagnostics" : ":\n" + String.join("\n", stray))));
                }
                return new CompileResult(exit == 0 && !hasErrors(diagnostics), diagnostics);
            } finally {
                Files.deleteIfExists(argfile);
            }
        } finally {
            if (scratch != null) PathUtil.deleteRecursively(scratch);
        }
    }

    private static Path writeArgfile(CompileRequest request, Path outDir) throws IOException {
        Path argfile = Files.createTempFile("jk-javac-", ".args");
        List<String> lines = new ArrayList<>();
        lines.add("-d");
        lines.add(quote(outDir.toAbsolutePath().toString()));
        lines.add("-encoding");
        lines.add(ActionKey.SOURCE_ENCODING);
        lines.add("--release");
        lines.add(Integer.toString(request.release()));
        if (!request.classpath().isEmpty()) {
            lines.add("-cp");
            lines.add(quote(Classpaths.join(request.classpath())));
        }
        if (!request.processorPath().isEmpty()) {
            // An explicit -processorpath both runs the processors and keeps them off
            // the compile classpath; modern javac won't auto-run classpath processors.
            lines.add("-processorpath");
            lines.add(quote(Classpaths.join(request.processorPath())));
        }
        for (String opt : request.extraOptions()) {
            lines.add(opt);
        }
        for (Path src : request.sources()) {
            lines.add(quote(src.toAbsolutePath().toString()));
        }
        Files.writeString(argfile, String.join("\n", lines), StandardCharsets.UTF_8);
        return argfile;
    }

    /** Wrap in double quotes if the value contains chars javac's argfile parser is fussy about. */
    private static String quote(String value) {
        if (!needsQuoting(value)) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean needsQuoting(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '#' || c == '\'' || c == '"') {
                return true;
            }
        }
        return false;
    }

    /** {@code "<n> errors" / "<n> warnings"} — javac's trailing tally, not part of a block. */
    private static final Pattern SUMMARY = Pattern.compile("^\\d+ (?:error|warning)s?$");

    /**
     * Header-less diagnostics javac emits without a {@code file:line:} anchor — fatal setup errors
     * ({@code error: error reading <jar>; zip END header not found}, {@code error: invalid flag}),
     * bare warnings ({@code warning: [options] …}), and trailing notes ({@code Note: … uses
     * unchecked or unsafe operations.}).
     */
    private static final Pattern BARE_DIAGNOSTIC =
            Pattern.compile("^(?<sev>error|warning|note): (?<msg>.*)$", Pattern.CASE_INSENSITIVE);

    /**
     * Parse javac stream into one diagnostic per block (header through next header/summary),
     * header-less bare lines, and unattributed {@code stray} leftovers.
     */
    private static List<CompileResult.Diagnostic> parseStream(Process process, List<String> stray) throws IOException {
        List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            // Header and body travel together: a block under construction always has the
            // severity, file and line its header carried, and nothing else does.
            Block block = null;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = DIAGNOSTIC.matcher(line);
                Matcher bare;
                if (m.matches()) {
                    if (block != null) {
                        diagnostics.add(block.diagnostic());
                    }
                    block = new Block(
                            parseSeverity(m.group("sev")),
                            Path.of(m.group("file")),
                            Long.parseLong(m.group("line")),
                            new StringBuilder(line));
                } else if (block != null && SUMMARY.matcher(line).matches()) {
                    // Tally line ends the current block and the diagnostic stream's body.
                    diagnostics.add(block.diagnostic());
                    block = null;
                } else if (block != null) {
                    // Snippet, caret, symbol:/location:, or wrapped message — keep verbatim.
                    block.text().append('\n').append(line);
                } else if ((bare = BARE_DIAGNOSTIC.matcher(line)).matches()) {
                    // HotSpot JEP 498 banners (lombok.permit, KSP IntelliJ containers, …) look
                    // like "WARNING: …" and would otherwise flood the warning channel. Real
                    // javac header-less warnings stay ("warning: [options] …").
                    if (isJvmHostNoise(line)) continue;
                    diagnostics.add(new CompileResult.Diagnostic(parseSeverity(bare.group("sev")), null, -1, -1, line));
                } else if (line.startsWith("javac: ")) {
                    // Launcher-level failure (invalid flag, file not found, bad argfile).
                    diagnostics.add(new CompileResult.Diagnostic(CompileResult.Severity.ERROR, null, -1, -1, line));
                } else if (SUMMARY.matcher(line).matches()) {
                    // Tally after a bare error ("1 error") — already accounted for.
                } else {
                    // Unattributed noise (usage text, crash trace) — kept for the caller.
                    stray.add(line);
                }
            }
            if (block != null) {
                diagnostics.add(block.diagnostic());
            }
        }
        return diagnostics;
    }

    /** One javac diagnostic being accumulated: its header's facts plus the verbatim text so far. */
    private record Block(CompileResult.Severity severity, Path file, long line, StringBuilder text) {
        CompileResult.Diagnostic diagnostic() {
            return new CompileResult.Diagnostic(severity, file, line, -1, text.toString());
        }
    }

    private static CompileResult.Severity parseSeverity(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "error" -> CompileResult.Severity.ERROR;
            case "warning" -> CompileResult.Severity.WARNING;
            case "note" -> CompileResult.Severity.NOTE;
            default -> CompileResult.Severity.OTHER;
        };
    }

    /**
     * HotSpot host banners about memory-access {@code sun.misc.Unsafe} (JEP 498), not javac
     * diagnostics. The four-line form names the caller on one line and asks maintainers on
     * another; match all of them so none leak into the UI.
     *
     * <p>Anchored case-sensitively to HotSpot's uppercase {@code WARNING: } prefix and exact
     * banner phrases: javac and annotation-processor {@code Messager} warnings use
     * lowercase {@code warning:}, and a processor warning that merely mentions
     * {@code sun.misc.Unsafe} or a deprecated method must reach the diagnostics channel. (The
     * banner's "terminally deprecated method" line also names {@code sun.misc.Unsafe}, so the
     * two matches below cover all four lines.)
     */
    static boolean isJvmHostNoise(String line) {
        if (!line.startsWith("WARNING: ")) return false;
        return line.contains("sun.misc.Unsafe")
                || line.contains("Please consider reporting this to the maintainers of class");
    }

    private static boolean hasErrors(List<CompileResult.Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == CompileResult.Severity.ERROR);
    }
}
