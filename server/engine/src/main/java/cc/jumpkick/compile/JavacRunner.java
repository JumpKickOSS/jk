// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.util.PathUtil;
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
        Path javac = javaHome.resolve("bin").resolve(HostPlatform.isWindows() ? "javac.exe" : "javac");
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
                command.addAll(JvmOptions.launcherFlags(1));
                // No PluginAot on bare `javac` — AOT is for `java … PluginMain` workers only
                // (jk-java-compiler ToolProvider host and kotlin-compiler). See PluginAot.
                command.add("@" + argfile);
                ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
                Process process = pb.start();
                List<String> stray = new ArrayList<>();
                List<CompileResult.Diagnostic> diagnostics = parseStream(process, stray);
                int exit = process.waitFor();
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac was interrupted", e);
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
        lines.add("UTF-8");
        lines.add("--release");
        lines.add(Integer.toString(request.release()));
        if (!request.classpath().isEmpty()) {
            lines.add("-cp");
            StringBuilder cp = new StringBuilder();
            String sep = System.getProperty("path.separator");
            for (int i = 0; i < request.classpath().size(); i++) {
                if (i > 0) cp.append(sep);
                cp.append(request.classpath().get(i).toAbsolutePath());
            }
            lines.add(quote(cp.toString()));
        }
        if (!request.processorPath().isEmpty()) {
            // An explicit -processorpath both runs the processors and keeps them off
            // the compile classpath; modern javac won't auto-run classpath processors.
            lines.add("-processorpath");
            StringBuilder pp = new StringBuilder();
            String sep = System.getProperty("path.separator");
            for (int i = 0; i < request.processorPath().size(); i++) {
                if (i > 0) pp.append(sep);
                pp.append(request.processorPath().get(i).toAbsolutePath());
            }
            lines.add(quote(pp.toString()));
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
            CompileResult.Severity sev = null;
            Path file = null;
            long lineNo = -1;
            StringBuilder block = null;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = DIAGNOSTIC.matcher(line);
                Matcher bare;
                if (m.matches()) {
                    if (block != null) {
                        diagnostics.add(new CompileResult.Diagnostic(sev, file, lineNo, -1, block.toString()));
                    }
                    sev = parseSeverity(m.group("sev"));
                    file = Path.of(m.group("file"));
                    lineNo = Long.parseLong(m.group("line"));
                    block = new StringBuilder(line);
                } else if (block != null && SUMMARY.matcher(line).matches()) {
                    // Tally line ends the current block and the diagnostic stream's body.
                    diagnostics.add(new CompileResult.Diagnostic(sev, file, lineNo, -1, block.toString()));
                    block = null;
                } else if (block != null) {
                    // Snippet, caret, symbol:/location:, or wrapped message — keep verbatim.
                    block.append('\n').append(line);
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
                diagnostics.add(new CompileResult.Diagnostic(sev, file, lineNo, -1, block.toString()));
            }
        }
        return diagnostics;
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
     */
    static boolean isJvmHostNoise(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("warning:")) return false;
        return lower.contains("sun.misc.unsafe")
                || lower.contains("terminally deprecated method")
                || lower.contains("please consider reporting this to the maintainers of class");
    }

    private static boolean hasErrors(List<CompileResult.Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == CompileResult.Severity.ERROR);
    }
}
