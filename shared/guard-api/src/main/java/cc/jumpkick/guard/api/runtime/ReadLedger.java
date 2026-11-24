// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What each guard of the last run read through {@link cc.jumpkick.guard.api.Text}, so a guard whose
 * reads all hold replays its report line instead of running again.
 *
 * <p>One JSON line per guard in {@code ledger.jsonl} beside the report: the guard's id, the run's
 * stamp, whether it may replay at all, each glob it listed with a digest of the match list, the
 * files it read, and its report line. The stamp covers what is not tree text: every class-path
 * entry's bytes (the suite, its libraries and this runtime), the JDK, the operating system and the
 * run's scope; a guard that takes the {@link cc.jumpkick.guard.api.Model} adds the model snapshot's bytes.
 * A guard replays only when its stamp is the same, every glob lists the same files, every file reads
 * the same, and its last outcome was a verdict ({@code ok}); a guard that takes {@code Facts} or
 * {@code Output}, or threw, skipped or found its owner missing, always runs.
 *
 * <p>A file is named once per ledger: a path line {@code {"path":…,"digest":…}} gives it the next
 * index, numbered from 0 in file order, ahead of the first guard line that reads it, and a guard
 * line lists its files as runs of indexes, {@code [first, count, first, count, …]}. So the ledger
 * grows with the files read and the lines found, not with the files times the guards reading them.
 *
 * <p>The ledger is read once when the runtime starts and rewritten as the run goes, path lines and
 * one guard line per guard as it finishes, so a run cut short leaves the lines of the guards it
 * finished.
 *
 * <p>A guard that runs again may still be spared most of its work: what it found in each file
 * through {@link cc.jumpkick.guard.api.Text#perFile} is kept in its line under {@code memo}, per
 * salt the runs of files it scanned and, by index, the lines found in each file where it found any.
 * The next run that invokes the guard under the same stamp hands those lines back for every file
 * that reads the same, so only the edited files are scanned again.
 */
final class ReadLedger implements TextView.Recorder {

    static final String FILE = "ledger.jsonl";

    /** The parameter types a guard may take and still replay: what it reads of them the ledger sees. */
    private static final Set<String> REPLAYABLE_PARAMS = Set.of("Text", "Model", "Violations");

    private final Path file;
    private final String stamp;
    private final @Nullable Path model;
    private final TextView text;
    private final Map<String, Line> previous;
    private final ConcurrentHashMap<String, Reads> reads = new ConcurrentHashMap<>();
    /** Per running guard, what it found per file this run, by path. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Found>> found = new ConcurrentHashMap<>();
    /** Per running guard, its line of the last run when that run had the same stamp. */
    private final ConcurrentHashMap<String, Line> lastFound = new ConcurrentHashMap<>();
    /** Each file's text digest: the view reads a file once per run, so its digest holds as long. */
    private final ConcurrentHashMap<String, String> digests = new ConcurrentHashMap<>();

    /** The index of each path and digest this run's file has named so far, by {@code path\0digest}. */
    private final Map<String, Integer> indexes = new HashMap<>();

    private @Nullable String modelDigest;

    private ReadLedger(Path file, String stamp, @Nullable Path model, TextView text, Map<String, Line> previous) {
        this.file = file;
        this.stamp = stamp;
        this.model = model;
        this.text = text;
        this.previous = previous;
    }

    /** What one guard read: the globs it listed and the files whose text it took. */
    static final class Reads {
        final Set<String> globs = ConcurrentHashMap.newKeySet();
        final Set<String> files = ConcurrentHashMap.newKeySet();
    }

    /**
     * The ledger beside {@code report}, recording what guards read through {@code text}: the last
     * run's lines are read, then the file is emptied for this run's. {@code stamp} is {@link
     * #stamp}'s for this run; {@code model} is the model snapshot, or {@code null}.
     */
    static ReadLedger open(Path report, String stamp, @Nullable Path model, TextView text) throws IOException {
        Path file = report.toAbsolutePath().normalize().resolveSibling(FILE);
        Map<String, Line> previous = read(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "", StandardCharsets.UTF_8);
        ReadLedger ledger = new ReadLedger(file, stamp, model, text, previous);
        text.recordTo(ledger);
        return ledger;
    }

    /**
     * The run's stamp: the bytes of every entry of {@code classpath} (a directory by every file
     * under it but the compile's stamps, which carry a clock), the JDK's version, the operating
     * system and {@code scope}, which names what the run judges.
     */
    static String stamp(List<Path> classpath, String scope) throws IOException {
        MessageDigest md = Hashing.newSha256();
        feed(md, "scope", scope);
        feed(md, "java", Runtime.version().toString());
        feed(md, "os", Os.name());
        for (Path entry : classpath) {
            Path abs = entry.toAbsolutePath().normalize();
            feed(md, "entry", abs.toString());
            if (Files.isDirectory(abs)) {
                TreeMap<String, Path> files = new TreeMap<>();
                PathUtil.forEachRegularFile(abs, d -> false, (f, attrs) -> {
                    String rel = abs.relativize(f).toString().replace('\\', '/');
                    if (!BuildStamps.isStampFile(rel)) files.put(rel, f);
                });
                for (var e : files.entrySet()) feed(md, e.getKey(), Hashing.sha256Hex(e.getValue()));
            } else if (Files.isRegularFile(abs)) {
                feed(md, "bytes", Hashing.sha256Hex(abs));
            } else {
                feed(md, "absent", "");
            }
        }
        return Hashing.hex(md.digest());
    }

    private static void feed(MessageDigest md, String tag, String value) {
        md.update((tag + "\0" + value + "\0").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void glob(String glob) {
        String id = GuardExtension.currentGuardId();
        if (id != null) reads.computeIfAbsent(id, k -> new Reads()).globs.add(glob);
    }

    @Override
    public void file(String path) {
        String id = GuardExtension.currentGuardId();
        if (id != null) reads.computeIfAbsent(id, k -> new Reads()).files.add(path);
    }

    /** What a guard found in one file: the digest of the text it scanned, and its lines per salt. */
    private record Found(String digest, ConcurrentHashMap<String, List<String>> bySalt) {}

    /**
     * Forget what guard {@code id} read so far: it is about to run. What it found per file in its last
     * run is kept for {@link #perFile} when that run had the same stamp.
     */
    void starting(String id, List<String> params) {
        reads.remove(id);
        found.put(id, new ConcurrentHashMap<>());
        Line last = previous.get(id);
        if (last != null && stampFor(params).equals(last.stamp())) lastFound.put(id, last);
        else lastFound.remove(id);
    }

    @Override
    public List<String> perFile(String path, String salt, Function<String, List<String>> scan) {
        String id = GuardExtension.currentGuardId();
        ConcurrentHashMap<String, Found> mine = id == null ? null : found.get(id);
        if (id == null || mine == null) return scan.apply(path);
        file(path);
        String digest = digest(path);
        if (digest.equals(UNREADABLE)) return scan.apply(path);
        Found now = mine.compute(
                path,
                (p, f) -> f != null && f.digest().equals(digest) ? f : new Found(digest, new ConcurrentHashMap<>()));
        List<String> lines = now.bySalt().get(salt);
        if (lines != null) return lines;
        lines = lastLines(id, path, digest, salt);
        if (lines == null) lines = List.copyOf(scan.apply(path));
        now.bySalt().put(salt, lines);
        return lines;
    }

    /** The lines guard {@code id} found in {@code path} under {@code salt} last run, when the text was the same. */
    private @Nullable List<String> lastLines(String id, String path, String digest, String salt) {
        Line last = lastFound.get(id);
        if (last == null || !digest.equals(last.files().get(path))) return null;
        Map<String, List<String>> bySalt = last.memo().get(salt);
        return bySalt == null ? null : bySalt.get(path);
    }

    /** What guard {@code id} found per file this run, per salt by path, as its ledger line keeps it. */
    private Map<String, Map<String, List<String>>> memoOf(String id) {
        Map<String, Map<String, List<String>>> memo = new TreeMap<>();
        ConcurrentHashMap<String, Found> mine = found.remove(id);
        lastFound.remove(id);
        if (mine == null) return memo;
        mine.forEach((path, f) -> f.bySalt().forEach((salt, lines) -> memo.computeIfAbsent(salt, k -> new TreeMap<>())
                .put(path, lines)));
        return memo;
    }

    /**
     * The report line guard {@code id} replays, or {@code null} when it runs; {@code why} hears the
     * reason it runs when it had a line to replay.
     */
    @Nullable
    String replay(String id, List<String> params, Consumer<String> why) {
        Line last = previous.get(id);
        if (last == null) return null;
        if (!replayable(params)) return null;
        if (!"ok".equals(last.outcome())) {
            why.accept("its last run was not a verdict");
            return null;
        }
        if (!stampFor(params).equals(last.stamp())) {
            why.accept("the suite, its class path, the JDK or what it judges changed");
            return null;
        }
        for (var e : last.globs().entrySet()) {
            if (!listingDigest(text.unrecordedFiles(e.getKey())).equals(e.getValue())) {
                why.accept("the files matching " + e.getKey() + " changed");
                return null;
            }
        }
        for (var e : last.files().entrySet()) {
            if (!digest(e.getKey()).equals(e.getValue())) {
                why.accept("it reads " + e.getKey() + ", which changed");
                return null;
            }
        }
        return last.line();
    }

    /**
     * Record guard {@code id}'s run: {@code line} is its report line, {@code outcome} the outcome in
     * it. A replayed guard records its last line again, with what it read then.
     */
    void record(String id, List<String> params, String outcome, String line, @Nullable String replayed)
            throws IOException {
        Line last = previous.remove(id);
        Line entry;
        if (replayed != null && last != null) {
            entry = new Line(
                    id,
                    stampFor(params),
                    outcome,
                    replayable(params),
                    last.globs(),
                    last.files(),
                    last.memo(),
                    replayed);
        } else {
            Reads r = reads.getOrDefault(id, new Reads());
            Map<String, String> globs = new TreeMap<>();
            Map<String, String> files = new TreeMap<>();
            for (String g : new TreeSet<>(r.globs)) globs.put(g, listingDigest(text.unrecordedFiles(g)));
            for (String f : new TreeSet<>(r.files)) files.put(f, digest(f));
            entry = new Line(
                    id,
                    stampFor(params),
                    outcome,
                    replayable(params),
                    globs,
                    files,
                    memoOf(id),
                    replayed != null ? replayed : line);
        }
        synchronized (this) {
            Files.writeString(
                    file, encode(entry), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /**
     * One guard's line: {@code files} is each file it read with its text's digest, and {@code memo}
     * what it found per salt, by file, in each file it scanned under that salt.
     */
    record Line(
            String id,
            @Nullable String stamp,
            @Nullable String outcome,
            boolean replayable,
            Map<String, String> globs,
            Map<String, String> files,
            Map<String, Map<String, List<String>>> memo,
            @Nullable String line) {}

    /**
     * The path lines {@code entry} names first, then its guard line, each ending in a newline. Caller
     * holds the monitor: the indexes are this run's file's.
     */
    private String encode(Line entry) {
        StringBuilder out = new StringBuilder();
        Map<String, Integer> index = new HashMap<>();
        entry.files().forEach((path, digest) -> {
            Integer i = indexes.get(path + "\0" + digest);
            if (i == null) {
                i = indexes.size();
                indexes.put(path + "\0" + digest, i);
                Map<String, Object> named = new LinkedHashMap<>();
                named.put("path", path);
                named.put("digest", digest);
                out.append(MiniJson.write(named)).append('\n');
            }
            index.put(path, i);
        });
        Map<String, Object> memo = new TreeMap<>();
        entry.memo().forEach((salt, byPath) -> {
            List<Integer> scanned = new ArrayList<>();
            Map<String, Object> lines = new TreeMap<>();
            byPath.forEach((path, found) -> {
                Integer i = index.get(path);
                if (i == null) return; // perFile notes each file it scans as read, so none is missed here
                scanned.add(i);
                if (!found.isEmpty()) lines.put(String.valueOf(i), found);
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("files", runs(scanned));
            m.put("found", lines);
            memo.put(salt, m);
        });
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", entry.id());
        line.put("stamp", entry.stamp());
        line.put("outcome", entry.outcome());
        line.put("replayable", entry.replayable());
        line.put("globs", entry.globs());
        line.put("files", runs(new ArrayList<>(index.values())));
        line.put("memo", memo);
        line.put("line", entry.line());
        return out.append(MiniJson.write(line)).append('\n').toString();
    }

    /** {@code indexes} as runs, {@code [first, count, …]} in ascending order. */
    private static List<Integer> runs(List<Integer> indexes) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(indexes));
        List<Integer> runs = new ArrayList<>();
        for (int i = 0; i < sorted.size(); ) {
            int first = sorted.get(i);
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j) == first + (j - i)) j++;
            runs.add(first);
            runs.add(j - i);
            i = j;
        }
        return runs;
    }

    /**
     * The guard lines of the ledger {@code file}, by guard id; empty when there is none. A line that
     * does not read, a killed run's half-written tail or another shape, is left out: its guard runs.
     */
    static Map<String, Line> read(Path file) throws IOException {
        Map<String, Line> lines = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return lines;
        List<String> paths = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try {
                Object parsed = MiniJson.parse(text);
                String id = MiniJson.str(parsed, "id");
                String path = MiniJson.str(parsed, "path");
                if (id != null) lines.put(id, decode(id, parsed, paths, digests));
                else if (path != null) {
                    paths.add(path);
                    digests.add(String.valueOf(MiniJson.str(parsed, "digest")));
                }
            } catch (RuntimeException torn) {
                // replays nothing
            }
        }
        return lines;
    }

    private static Line decode(String id, @Nullable Object parsed, List<String> paths, List<String> digests) {
        Map<String, String> globs = new TreeMap<>();
        if (MiniJson.get(parsed, "globs") instanceof Map<?, ?> g)
            g.forEach((k, v) -> globs.put(String.valueOf(k), String.valueOf(v)));
        Map<String, String> files = new TreeMap<>();
        for (int i : indexes(MiniJson.get(parsed, "files"), paths.size())) files.put(paths.get(i), digests.get(i));
        Map<String, Map<String, List<String>>> memo = new HashMap<>();
        if (MiniJson.get(parsed, "memo") instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                Map<String, List<String>> byPath = new HashMap<>();
                Object found = MiniJson.get(e.getValue(), "found");
                for (int i : indexes(MiniJson.get(e.getValue(), "files"), paths.size())) {
                    Object lines = found instanceof Map<?, ?> f ? f.get(String.valueOf(i)) : null;
                    byPath.put(paths.get(i), strings(lines));
                }
                memo.put(String.valueOf(e.getKey()), byPath);
            }
        }
        return new Line(
                id,
                MiniJson.str(parsed, "stamp"),
                MiniJson.str(parsed, "outcome"),
                Boolean.TRUE.equals(MiniJson.get(parsed, "replayable")),
                globs,
                files,
                memo,
                MiniJson.str(parsed, "line"));
    }

    /** The indexes {@code runs} lists, each below {@code size}; anything else does not read. */
    private static List<Integer> indexes(@Nullable Object runs, int size) {
        if (!(runs instanceof List<?> list) || list.size() % 2 != 0) throw new IllegalArgumentException("runs");
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < list.size(); k += 2) {
            if (!(list.get(k) instanceof Long first) || !(list.get(k + 1) instanceof Long count))
                throw new IllegalArgumentException("runs");
            if (first < 0 || count < 0 || first + count > size) throw new IllegalArgumentException("runs");
            for (long i = first; i < first + count; i++) out.add((int) i);
        }
        return out;
    }

    private static List<String> strings(@Nullable Object lines) {
        if (lines == null) return List.of();
        if (!(lines instanceof List<?> list)) throw new IllegalArgumentException("lines");
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof String s)) throw new IllegalArgumentException("lines");
            out.add(s);
        }
        return List.copyOf(out);
    }

    /** Whether a guard taking {@code params} may replay: the ledger sees everything it reads of them. */
    static boolean replayable(List<String> params) {
        return REPLAYABLE_PARAMS.containsAll(params);
    }

    private String stampFor(List<String> params) {
        if (!params.contains("Model")) return stamp;
        return Hashing.sha256Hex(stamp + "\0model\0" + modelDigest());
    }

    private synchronized String modelDigest() {
        if (modelDigest == null) {
            try {
                modelDigest = model != null && Files.isRegularFile(model) ? Hashing.sha256Hex(model) : "none";
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return modelDigest;
    }

    static String listingDigest(List<String> files) {
        return Hashing.sha256Hex(String.join("\n", files)).substring(0, 16);
    }

    /** The digest a file that does not read is noted with. */
    static final String UNREADABLE = "null";

    /** The digest of a file's text as the view reads it; {@link #UNREADABLE} when it does not read. */
    private String digest(String path) {
        return digests.computeIfAbsent(path, p -> textDigest(text, p));
    }

    /** The digest of {@code path}'s text as {@code view} reads it, as a ledger line notes it. */
    static String textDigest(TextView view, String path) {
        String body = view.unrecordedText(path);
        return body == null ? UNREADABLE : Hashing.sha256Hex(body).substring(0, 16);
    }
}
