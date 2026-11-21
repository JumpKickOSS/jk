// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.jsonl.JsonFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** BSP {@code build/publishDiagnostics} items from a build's compiler messages. */
final class BspDiagnostics {

    /** ANSI CSI sequences — the colour a compiler paints a message with; an IDE shows them as text. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private BspDiagnostics() {}

    /** {@code text} without its ANSI escapes. */
    static String plain(String text) {
        return text.indexOf('\u001B') < 0 ? text : ANSI.matcher(text).replaceAll("");
    }

    /**
     * BSP severity: 1 = error, 2 = warning. A block is read without its colour escapes, so a
     * scalac message is parsed and shown as text; an unparseable block lands at project root line 0.
     * A relative path is the compiled module's file when it has one, else the project's.
     */
    static void collectDiagnostics(
            Map<String, List<String>> byFile,
            @Nullable List<String> raw,
            int severity,
            @Nullable Path moduleDir,
            Path projectDir) {
        if (raw == null) return;
        for (String coloured : raw) {
            if (coloured == null || coloured.isBlank()) continue;
            String text = plain(coloured);
            CompilerLocus locus = CompilerLocus.parse(text);
            if (locus != null) {
                byFile.computeIfAbsent(
                                BspServer.pathUri(fileOf(locus.file(), moduleDir, projectDir)), k -> new ArrayList<>())
                        .add(diagnosticJson(
                                Math.max(0, locus.line() - 1), Math.max(0, locus.col() - 1), severity, text.strip()));
            } else {
                byFile.computeIfAbsent(BspServer.pathUri(projectDir), k -> new ArrayList<>())
                        .add(diagnosticJson(0, 0, severity, text));
            }
        }
    }

    /** {@code file} as named, made absolute against the module or the project rather than the working directory. */
    static Path fileOf(String file, @Nullable Path moduleDir, Path projectDir) {
        Path named = Path.of(file);
        if (named.isAbsolute()) return named;
        if (moduleDir != null && Files.exists(moduleDir.resolve(named))) return moduleDir.resolve(named);
        return projectDir.resolve(named);
    }

    static String diagnosticJson(int line, int col, int severity, String message) {
        String position = JsonFields.object()
                .number("line", line)
                .number("character", col)
                .finish();
        return JsonFields.object()
                .token(
                        "range",
                        JsonFields.object()
                                .token("start", position)
                                .token("end", position)
                                .finish())
                .number("severity", severity)
                .string("message", message)
                .finish();
    }
}
