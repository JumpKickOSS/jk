// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.diagnostic.CompilerLocus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The package a kotlinc {@code Unresolved reference 'x'} names when the reference sits on an
 * {@code import} line: kotlinc's counterpart of javac's {@code package … does not exist}. kotlinc
 * reports text only, so the source line is read from the file the header names.
 */
final class KotlinImports {

    private static final Pattern UNRESOLVED = Pattern.compile("(?i)unresolved reference[: ]+'?([^'.\\s]+)'?");

    /** {@code import a.b.C}, {@code import a.b.*} or {@code import a.b.C as D}; backticks allowed. */
    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+([\\w`]+(?:\\.[\\w`]+)*)(\\.\\*)?(?:\\s+as\\s+[\\w`]+)?\\s*;?\\s*$");

    private KotlinImports() {}

    /**
     * The packages an unresolved import could have come from, most specific first; empty for any
     * other diagnostic or when the source line cannot be read.
     */
    static List<String> missingPackages(CompileResult.Diagnostic d) {
        if (d.severity() != CompileResult.Severity.ERROR) return List.of();
        String first = d.message().lines().findFirst().orElse("");
        Matcher unresolved = UNRESOLVED.matcher(first);
        if (!unresolved.find()) return List.of();
        Path file = d.source();
        long line = d.line();
        if (file == null || line <= 0) {
            CompilerLocus locus = CompilerLocus.parse(withoutSeverity(first));
            if (locus == null || locus.line() <= 0) return List.of();
            file = Path.of(locus.file());
            line = locus.line();
        }
        String source = line(file, line);
        return source == null ? List.of() : packages(source, unresolved.group(1));
    }

    /**
     * The candidate packages of an {@code import} line whose path holds {@code reference}: the path
     * itself for a star import, else the path without its last segment, and that package's parent
     * too when its own last segment is capitalized (a nested class).
     */
    static List<String> packages(String sourceLine, String reference) {
        Matcher m = IMPORT.matcher(sourceLine);
        if (!m.matches()) return List.of();
        String path = m.group(1).replace("`", "");
        List<String> segments = List.of(path.split("\\."));
        if (!segments.contains(reference)) return List.of();
        String pkg;
        if (m.group(2) != null) {
            pkg = path;
        } else {
            int dot = path.lastIndexOf('.');
            if (dot <= 0) return List.of();
            pkg = path.substring(0, dot);
        }
        List<String> out = new ArrayList<>();
        out.add(pkg);
        int dot = pkg.lastIndexOf('.');
        if (dot > 0 && Character.isUpperCase(pkg.charAt(dot + 1))) out.add(pkg.substring(0, dot));
        return out;
    }

    /** kotlinc's header may carry a leading {@code e: } or {@code error: }. */
    private static String withoutSeverity(String header) {
        if (header.startsWith("e: ")) return header.substring(3);
        if (header.startsWith("error: ")) return header.substring(7);
        return header;
    }

    private static @Nullable String line(Path file, long line) {
        if (!Files.isRegularFile(file)) return null;
        try (var lines = Files.lines(file)) {
            return lines.skip(line - 1).findFirst().orElse(null);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }
}
