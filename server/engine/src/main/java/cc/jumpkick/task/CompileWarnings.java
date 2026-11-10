// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.compile.CompileResult.Diagnostic;
import cc.jumpkick.compile.CompileResult.Severity;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The warnings a compile reported, kept so a build that does not run the compiler says them again:
 * an action record's {@code said}, the per-source ledger beside a compiler's incremental state, and
 * the file of the warnings the classes on disk were compiled with.
 *
 * <p>One warning is one JSON line. Its source is spelled {@link PortablePath#key module-relative},
 * and the source's absolute path inside the message is a placeholder, so a record written in one
 * checkout reads back naming the same file in another.
 */
public final class CompileWarnings {

    /** The per-source ledger in a compiler's incremental state dir; it moves only with that state. */
    static final String LEDGER = "warnings";

    private static final String SOURCE = "${source}";
    private static final String SOURCE_URI = "${source-uri}";

    private CompileWarnings() {}

    /** What of {@code diagnostics} is replayed: every non-error, or only the warnings when {@code warningsOnly}. */
    public static List<Diagnostic> replayable(List<Diagnostic> diagnostics, boolean warningsOnly) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (d.severity() == Severity.ERROR) continue;
            if (warningsOnly && d.severity() != Severity.WARNING) continue;
            out.add(d);
        }
        return out;
    }

    /** {@code warnings} as stored lines. */
    public static List<String> said(List<Diagnostic> warnings) {
        List<String> out = new ArrayList<>(warnings.size());
        for (Diagnostic d : warnings) out.add(encode(d));
        return out;
    }

    /** Stored lines back as warnings, each source resolved against this compile's {@code sources}. */
    public static List<Diagnostic> fromSaid(List<String> said, Collection<Path> sources) {
        Map<String, Path> byKey = byKey(sources);
        List<Diagnostic> out = new ArrayList<>(said.size());
        for (String line : said) {
            Diagnostic d = decode(line, byKey::get);
            if (d != null) out.add(d);
        }
        return out;
    }

    /**
     * The warnings after an incremental compile: {@code all} is {@code kept}'s for every source still
     * in the source set that the compile did not recompile, then the compile's own, sorted by source,
     * line and column so a merge and a whole compile of the same sources read the same.
     */
    public record Merged(List<Diagnostic> all, List<Diagnostic> kept) {}

    /**
     * Merge {@code previous} with the {@code fresh} warnings of a compile that recompiled {@code
     * compiled} (null: the whole source set) out of {@code sources}. A file that got a fresh warning
     * counts as recompiled. A warning with no source belongs to whichever compile last ran, so it is
     * kept only when nothing was recompiled.
     */
    public static Merged merge(
            List<Diagnostic> previous,
            @Nullable Collection<Path> compiled,
            Collection<Path> sources,
            List<Diagnostic> fresh) {
        List<Diagnostic> kept = new ArrayList<>();
        if (compiled != null) {
            Set<Path> recompiled = normalized(compiled);
            for (Diagnostic d : fresh) {
                if (d.source() != null) recompiled.add(normal(d.source()));
            }
            Set<Path> present = normalized(sources);
            for (Diagnostic d : previous) {
                Path src = d.source();
                boolean keep = src == null
                        ? recompiled.isEmpty()
                        : present.contains(normal(src)) && !recompiled.contains(normal(src));
                if (keep) kept.add(d);
            }
        }
        List<Diagnostic> all = new ArrayList<>(kept);
        all.addAll(fresh);
        all.sort(ORDER);
        return new Merged(List.copyOf(all), List.copyOf(kept));
    }

    /** Whether {@code stateDir} holds a ledger: a state dir without one cannot say what its untouched sources warned. */
    public static boolean hasLedger(Path stateDir) {
        return Files.isRegularFile(stateDir.resolve(LEDGER));
    }

    /** The ledger in {@code stateDir}, resolved against {@code sources}; empty when there is none. */
    public static List<Diagnostic> readLedger(Path stateDir, Collection<Path> sources) {
        return readCurrent(stateDir.resolve(LEDGER), sources)
                .map(CompileWarnings::warningsOf)
                .orElse(List.of());
    }

    /** Replace the ledger in {@code stateDir} with {@code warnings}. */
    public static void writeLedger(Path stateDir, List<Diagnostic> warnings) throws IOException {
        writeLines(stateDir.resolve(LEDGER), said(warnings));
    }

    /** One kept warning and the tool that said it ({@code javac}, {@code kotlinc}, {@code groovyc}). */
    public record Kept(String tool, Diagnostic warning) {}

    /**
     * The warnings files a module keeps, in the order a build says them: a step's own, named by the
     * step, and a KSP round's inside another step, named {@code <step>+ksp}. The order a module the
     * build does not run says its warnings in.
     */
    public static final List<String> FILES = List.of(
            TaskNames.KSP,
            TaskNames.COMPILE_KOTLIN,
            TaskNames.COMPILE_GROOVY,
            TaskNames.COMPILE_JAVA,
            TaskNames.COMPILE_VERSIONS,
            TaskNames.COMPILE_TEST_FIXTURES,
            roundFile(TaskNames.COMPILE_TEST),
            TaskNames.COMPILE_TEST,
            TaskNames.COMPILE_GUARD);

    /** The steps that compile tests: a build that skips tests neither runs nor says theirs. */
    public static final Set<String> TEST_STEPS =
            Set.of(TaskNames.COMPILE_TEST_FIXTURES, TaskNames.COMPILE_TEST, TaskNames.COMPILE_GUARD);

    /** The warnings file of the KSP round {@code producer} runs: its own step's, or {@code <step>+ksp}. */
    public static String roundFile(String producer) {
        return TaskNames.KSP.equals(producer) ? producer : producer + "+ksp";
    }

    /** The step whose warnings {@code file} keeps. */
    public static String stepOf(String file) {
        int plus = file.indexOf('+');
        return plus < 0 ? file : file.substring(0, plus);
    }

    /** {@code kept}, with their tools, as stored lines: the {@code said} of a step that is not one compiler. */
    public static List<String> keptSaid(List<Kept> kept) {
        List<String> out = new ArrayList<>(kept.size());
        for (Kept k : kept) out.add(encode(k.tool(), k.warning()));
        return out;
    }

    /** Lines {@link #keptSaid} stored, back as warnings and their tools. */
    public static List<Kept> keptFromSaid(List<String> said) {
        List<Kept> out = new ArrayList<>(said.size());
        for (String line : said) {
            Diagnostic d = decode(line, key -> null);
            if (d != null) out.add(new Kept(Objects.requireNonNullElse(Jsonl.str(line, "tool"), ""), d));
        }
        return out;
    }

    /**
     * Where a module keeps the warnings its outputs on disk were made with, {@code file} one of
     * {@link #FILES}: {@code target/incremental/warnings/<file>}. Written by every run of its step;
     * read by a build that finds the step up to date, and by one that does not run the module at all.
     */
    public static Path currentFile(Path incrementalDir, String file) {
        return incrementalDir.resolve("warnings").resolve(file);
    }

    /** Replace the file at {@code file} with {@code warnings}, every one said by {@code tool}. */
    public static void writeCurrent(Path file, String tool, List<Diagnostic> warnings) {
        List<Kept> kept = new ArrayList<>(warnings.size());
        for (Diagnostic d : warnings) kept.add(new Kept(tool, d));
        writeCurrent(file, kept);
    }

    /** Replace the file at {@code file} with {@code warnings}. Best-effort: a file not written is a compile next time. */
    public static void writeCurrent(Path file, List<Kept> warnings) {
        try {
            writeLines(file, keptSaid(warnings));
        } catch (IOException e) {
            Log.debug("compile warnings not kept at " + file, e);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // the stamp skip finds no file and compiles
            }
        }
    }

    /** The warnings at {@code file}, each source resolved against {@code sources}; empty when there is no such file. */
    public static Optional<List<Kept>> readCurrent(Path file, Collection<Path> sources) {
        Map<String, Path> byKey = byKey(sources);
        return readKept(file, byKey::get);
    }

    /**
     * The warnings at {@code file} of a module the build does not run, each source resolved under
     * {@code moduleDir}, which is what its stored spelling is relative to.
     */
    public static List<Kept> readCurrentUnder(Path file, Path moduleDir) {
        Path root = normal(moduleDir);
        return readKept(file, key -> {
                    Path p = root.resolve(key).normalize();
                    return Files.isRegularFile(p) ? p : null;
                })
                .orElse(List.of());
    }

    /** The warnings of {@code kept}, without their tools. */
    public static List<Diagnostic> warningsOf(List<Kept> kept) {
        List<Diagnostic> out = new ArrayList<>(kept.size());
        for (Kept k : kept) out.add(k.warning());
        return out;
    }

    private static Optional<List<Kept>> readKept(Path file, Function<String, @Nullable Path> resolve) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            List<Kept> out = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                Diagnostic d = decode(line, resolve);
                if (d != null) out.add(new Kept(Objects.requireNonNullElse(Jsonl.str(line, "tool"), ""), d));
            }
            return Optional.of(out);
        } catch (IOException | RuntimeException e) {
            Log.debug("compile warnings unreadable at " + file, e);
            return Optional.empty();
        }
    }

    /** Replace {@code file} with {@code lines} whole: a reader never sees half of it. */
    private static void writeLines(Path file, List<String> lines) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    static String encode(Diagnostic d) {
        return encode(null, d);
    }

    private static String encode(@Nullable String tool, Diagnostic d) {
        Path src = d.source();
        String message = d.message();
        String key = null;
        if (src != null) {
            Path abs = normal(src);
            key = PortablePath.key(abs);
            if (!message.contains(SOURCE)) {
                message = message.replace(abs.toUri().toString(), SOURCE_URI)
                        .replace("file://" + abs, SOURCE_URI)
                        .replace(abs.toString(), SOURCE);
            }
        }
        return JsonFields.object()
                .optionalString("tool", tool)
                .string("sev", d.severity().name())
                .optionalString("src", key)
                .number("line", d.line())
                .number("col", d.column())
                .string("key", d.key())
                .string("msg", message)
                .finish();
    }

    private static @Nullable Diagnostic decode(String line, Function<String, @Nullable Path> resolve) {
        String msg = Jsonl.str(line, "msg");
        if (msg == null) return null;
        Severity severity = Severity.fromName(Jsonl.str(line, "sev"));
        String key = Jsonl.str(line, "src");
        Path src = null;
        if (key != null) {
            // A source no longer in the set keeps its stored spelling: still a file, never this
            // compile's, so a merge drops it rather than taking it for a warning with no file.
            Path current = resolve.apply(key);
            src = current != null ? current : Path.of(key);
            String spelled = current != null ? current.toString() : key;
            String uri = current != null ? current.toUri().toString() : key;
            msg = msg.replace(SOURCE_URI, uri).replace(SOURCE, spelled);
        }
        return new Diagnostic(
                severity,
                src,
                Jsonl.longValue(line, "line", 0),
                Jsonl.longValue(line, "col", 0),
                msg,
                Objects.requireNonNullElse(Jsonl.str(line, "key"), ""));
    }

    private static Map<String, Path> byKey(Collection<Path> sources) {
        Map<String, Path> out = new HashMap<>();
        for (Path p : sources) {
            Path abs = normal(p);
            out.putIfAbsent(PortablePath.key(abs), abs);
        }
        return out;
    }

    private static Set<Path> normalized(Collection<Path> paths) {
        Set<Path> out = new HashSet<>();
        for (Path p : paths) out.add(normal(p));
        return out;
    }

    private static Path normal(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /** Source, then line, then column: the order a merge reads in. */
    public static final Comparator<Diagnostic> ORDER = Comparator.comparing((Diagnostic d) ->
                    d.source() == null ? "" : normal(d.source()).toString())
            .thenComparingLong(Diagnostic::line)
            .thenComparingLong(Diagnostic::column);
}
