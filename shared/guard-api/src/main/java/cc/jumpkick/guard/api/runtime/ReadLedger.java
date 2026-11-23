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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What each guard of the last run read through {@link cc.jumpkick.guard.api.Text}, so a guard whose
 * reads all hold replays its report line instead of running again.
 *
 * <p>One JSON line per guard in {@code ledger.jsonl} beside the report: the guard's id, the run's
 * stamp, each glob it listed with a digest of the match list, each file it read with a digest of
 * its text, and its report line. The stamp covers what is not tree text: every class-path entry's
 * bytes (the suite, its libraries and this runtime), the JDK, the operating system and the run's
 * scope; a guard that takes the {@link cc.jumpkick.guard.api.Model} adds the model snapshot's bytes.
 * A guard replays only when its stamp is the same, every glob lists the same files, every file reads
 * the same, and its last outcome was a verdict ({@code ok}); a guard that takes {@code Facts} or
 * {@code Output}, or threw, skipped or found its owner missing, always runs.
 *
 * <p>The ledger is read once when the runtime starts and rewritten as the run goes, one line per
 * guard as it finishes, so a run cut short leaves the lines of the guards it finished.
 */
final class ReadLedger implements TextView.Recorder {

    static final String FILE = "ledger.jsonl";

    /** The parameter types a guard may take and still replay: what it reads of them the ledger sees. */
    private static final Set<String> REPLAYABLE_PARAMS = Set.of("Text", "Model", "Violations");

    private final Path file;
    private final String stamp;
    private final @Nullable Path model;
    private final TextView text;
    private final Map<String, Map<?, ?>> previous;
    private final ConcurrentHashMap<String, Reads> reads = new ConcurrentHashMap<>();
    private @Nullable String modelDigest;

    private ReadLedger(Path file, String stamp, @Nullable Path model, TextView text, Map<String, Map<?, ?>> previous) {
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
        Map<String, Map<?, ?>> previous = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Object parsed;
                try {
                    parsed = MiniJson.parse(line);
                } catch (RuntimeException torn) {
                    continue; // a line a killed run left half written replays nothing
                }
                String id = MiniJson.str(parsed, "id");
                if (parsed instanceof Map<?, ?> m && id != null) previous.put(id, m);
            }
        }
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

    /** Forget what guard {@code id} read so far: it is about to run. */
    void starting(String id) {
        reads.remove(id);
    }

    /**
     * The report line guard {@code id} replays, or {@code null} when it runs; {@code why} hears the
     * reason it runs when it had a line to replay.
     */
    @Nullable
    String replay(String id, List<String> params, Consumer<String> why) {
        Map<?, ?> last = previous.get(id);
        if (last == null) return null;
        if (!REPLAYABLE_PARAMS.containsAll(params)) return null;
        if (!"ok".equals(MiniJson.str(last, "outcome"))) {
            why.accept("its last run was not a verdict");
            return null;
        }
        if (!stampFor(params).equals(MiniJson.str(last, "stamp"))) {
            why.accept("the suite, its class path, the JDK or what it judges changed");
            return null;
        }
        if (MiniJson.get(last, "globs") instanceof Map<?, ?> globs) {
            for (var e : globs.entrySet()) {
                String glob = String.valueOf(e.getKey());
                if (!listingDigest(text.unrecordedFiles(glob)).equals(e.getValue())) {
                    why.accept("the files matching " + glob + " changed");
                    return null;
                }
            }
        }
        if (MiniJson.get(last, "files") instanceof Map<?, ?> files) {
            for (var e : files.entrySet()) {
                String path = String.valueOf(e.getKey());
                if (!String.valueOf(textDigest(text, path)).equals(e.getValue())) {
                    why.accept("it reads " + path + ", which changed");
                    return null;
                }
            }
        }
        return MiniJson.str(last, "line");
    }

    /**
     * Record guard {@code id}'s run: {@code line} is its report line, {@code outcome} the outcome in
     * it. A replayed guard records its last line again, with what it read then.
     */
    void record(String id, List<String> params, String outcome, String line, @Nullable String replayed)
            throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("stamp", stampFor(params));
        entry.put("outcome", outcome);
        Map<?, ?> last = previous.remove(id);
        if (replayed == null) last = null;
        if (last != null) {
            entry.put("globs", MiniJson.get(last, "globs"));
            entry.put("files", MiniJson.get(last, "files"));
        } else {
            Reads r = reads.getOrDefault(id, new Reads());
            Map<String, String> globs = new TreeMap<>();
            Map<String, String> files = new TreeMap<>();
            for (String g : new TreeSet<>(r.globs)) globs.put(g, listingDigest(text.unrecordedFiles(g)));
            for (String f : new TreeSet<>(r.files)) files.put(f, String.valueOf(textDigest(text, f)));
            entry.put("globs", globs);
            entry.put("files", files);
        }
        entry.put("line", replayed != null ? replayed : line);
        synchronized (this) {
            Files.writeString(
                    file,
                    MiniJson.write(entry) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
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

    private static String listingDigest(List<String> files) {
        return Hashing.sha256Hex(String.join("\n", files)).substring(0, 16);
    }

    /** The digest of a file's text as the view reads it; {@code null} when it does not read. */
    private static @Nullable String textDigest(TextView text, String path) {
        String body = text.unrecordedText(path);
        return body == null ? null : Hashing.sha256Hex(body).substring(0, 16);
    }
}
