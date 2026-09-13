// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * On-disk {@code target/incremental/main-src-api.idx}: one row per Java source the module's last
 * compile-main saw — module-relative path, content hash, {@link JavaSourceApi declaration
 * digest}. The baseline {@code jk explain} compares a dirty module's sources against before any
 * compile runs: a source whose content moved but whose declaration digest did not is a body-only
 * edit, and the module's consumers are likely to keep their compile keys; one whose digest moved
 * is an API change and they are likely to recompile. Advanced by exactly what each compile did,
 * like {@code AbiIndex}: only the sources that compiled are re-digested.
 */
public final class SourceApiIndex {

    public static final String FILE_NAME = "main-src-api.idx";

    private SourceApiIndex() {}

    /**
     * One source's baseline: its content hash, its {@link JavaSourceApi.View#DECLARATIONS}
     * digest and its {@link JavaSourceApi.View#EXPORTED} digest.
     */
    public record Row(String contentSha, String apiToken, String exportedToken) {}

    /** What the hint concluded, and the sources that decided it. */
    public enum Kind {
        /** Every changed Java source kept its declaration digest. */
        BODY_ONLY,
        /** At least one Java source's declarations moved, or a source appeared or disappeared. */
        API_CHANGED,
        /** No baseline, or a change the index cannot classify (a non-Java source moved). */
        UNKNOWN
    }

    /** The hint for a module: {@code files} names the sources whose declarations moved, module-relative. */
    public record Hint(Kind kind, List<String> files) {
        public Hint {
            files = List.copyOf(files);
        }

        public static final Hint UNKNOWN = new Hint(Kind.UNKNOWN, List.of());
    }

    public static Path path(Path buildDir) {
        return buildDir.resolve("incremental").resolve(FILE_NAME);
    }

    public static Map<String, Row> load(Path file) throws IOException {
        Map<String, Row> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                String[] p = line.split("\t", -1);
                if (p.length == 4) out.put(p[0], new Row(p[1], p[2], p[3]));
            });
        }
        return out;
    }

    public static void write(Path file, Map<String, Row> rows) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (var e : rows.entrySet()) {
            sb.append(e.getKey())
                    .append('\t')
                    .append(e.getValue().contentSha())
                    .append('\t')
                    .append(e.getValue().apiToken())
                    .append('\t')
                    .append(e.getValue().exportedToken())
                    .append('\n');
        }
        AtomicWrites.replace(file, sb.toString());
    }

    /** The baseline of every Java source in {@code sources}, freshly digested. */
    public static Map<String, Row> of(Path moduleDir, List<Path> sources) throws IOException {
        return updated(Map.of(), moduleDir, sources, sources);
    }

    /**
     * {@code previous} advanced by one compile: the rows of {@code compiled} and of every source
     * whose content no longer matches its row are re-digested, rows whose source is no longer in
     * {@code current} are dropped, and every other row carries forward. A restored compile
     * compiles nothing, yet its sources may sit at a content the index has never seen; the
     * content check is what keeps the baseline the state of the classes on disk. Only {@code
     * .java} files under the module are indexed.
     */
    public static Map<String, Row> updated(
            Map<String, Row> previous, Path moduleDir, List<Path> compiled, List<Path> current) throws IOException {
        Path module = moduleDir.toAbsolutePath().normalize();
        Map<String, Path> currentByRel = new LinkedHashMap<>();
        for (Path src : current) {
            String rel = relative(module, src);
            if (rel != null) currentByRel.put(rel, src.toAbsolutePath().normalize());
        }
        Map<String, Row> out = new LinkedHashMap<>();
        for (var e : previous.entrySet()) {
            if (currentByRel.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        LinkedHashSet<Path> toDigest = new LinkedHashSet<>();
        for (Path src : compiled) {
            String rel = relative(module, src);
            if (rel != null && currentByRel.containsKey(rel))
                toDigest.add(src.toAbsolutePath().normalize());
        }
        for (var e : currentByRel.entrySet()) {
            Row row = out.get(e.getKey());
            if (row == null || !row.contentSha().equals(FileHashMemo.contentHash(e.getValue()))) {
                toDigest.add(e.getValue());
            }
        }
        if (!toDigest.isEmpty()) {
            List<Path> files = new ArrayList<>(toDigest);
            Map<Path, String> declarations = JavaSourceApi.digests(files, JavaSourceApi.View.DECLARATIONS);
            Map<Path, String> exported = JavaSourceApi.digests(files, JavaSourceApi.View.EXPORTED);
            for (Path src : files) {
                String rel = Objects.requireNonNull(relative(module, src), "under the module");
                out.put(
                        rel,
                        new Row(
                                FileHashMemo.contentHash(src),
                                Objects.requireNonNull(declarations.get(src), "digest"),
                                Objects.requireNonNull(exported.get(src), "digest")));
            }
        }
        return out;
    }

    /**
     * Classify the module's current Java sources against the baseline: unchanged content is
     * skipped, changed content is re-digested and compared, a source without a row or a row
     * without a source is an API change. With {@code privateMembersMatter} — the module runs an
     * annotation processor, which may shape public output from a private field — the comparison
     * is the full declaration digest; otherwise the exported one, and a private edit reads as
     * body-only. {@code sources} that are not {@code .java} make the answer {@link Kind#UNKNOWN}:
     * the index knows nothing about them.
     */
    public static Hint classify(
            Path moduleDir, Map<String, Row> baseline, List<Path> sources, boolean privateMembersMatter)
            throws IOException {
        if (baseline.isEmpty()) return Hint.UNKNOWN;
        Path module = moduleDir.toAbsolutePath().normalize();
        List<String> moved = new ArrayList<>();
        Map<String, Path> seen = new LinkedHashMap<>();
        List<Path> changed = new ArrayList<>();
        for (Path src : sources) {
            Path abs = src.toAbsolutePath().normalize();
            if (!abs.toString().endsWith(".java")) {
                // A Scala or generated source the index does not describe: no baseline for it.
                return Hint.UNKNOWN;
            }
            String rel = relative(module, abs);
            if (rel == null) return Hint.UNKNOWN;
            seen.put(rel, abs);
            Row row = baseline.get(rel);
            if (row == null) {
                moved.add(rel);
                continue;
            }
            if (!row.contentSha().equals(FileHashMemo.contentHash(abs))) changed.add(abs);
        }
        for (String rel : baseline.keySet()) {
            if (!seen.containsKey(rel)) moved.add(rel);
        }
        if (!changed.isEmpty()) {
            JavaSourceApi.View view =
                    privateMembersMatter ? JavaSourceApi.View.DECLARATIONS : JavaSourceApi.View.EXPORTED;
            Map<Path, String> digests = JavaSourceApi.digests(changed, view);
            for (Path abs : changed) {
                String rel = Objects.requireNonNull(relative(module, abs), "under the module");
                String token = Objects.requireNonNull(digests.get(abs), "digest");
                Row row = Objects.requireNonNull(baseline.get(rel), "baseline row");
                if (!JavaSourceApi.parsed(token)) return Hint.UNKNOWN;
                String recorded = privateMembersMatter ? row.apiToken() : row.exportedToken();
                if (!token.equals(recorded)) moved.add(rel);
            }
        }
        if (!moved.isEmpty()) return new Hint(Kind.API_CHANGED, moved);
        return new Hint(Kind.BODY_ONLY, List.of());
    }

    /** {@code src} relative to the module with forward slashes, or null when it is not a {@code .java} under it. */
    private static @Nullable String relative(Path module, Path src) {
        Path abs = src.toAbsolutePath().normalize();
        if (!abs.startsWith(module) || !abs.toString().endsWith(".java")) return null;
        return module.relativize(abs).toString().replace('\\', '/');
    }
}
