// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlValues;
import cc.jumpkick.plugin.protocol.MiniJson;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Best-effort machine build history (engine/project/step tiers) at {@code ~/.local/state/jk/builds/metrics.json}.
 * Outcome buckets stay separate so estimators only learn from {@link Entry#ok}; {@link #record} is
 * locked atomic replace.
 */
public final class BuildMetrics {

    /** The current on-disk schema version. */
    public static final int SCHEMA = 1;

    /** In-memory key separator; never appears in a sane path or step name. */
    private static final char SEP = '\n';

    private static final ConcurrentHashMap<Path, BuildMetrics> MEMO = new ConcurrentHashMap<>();

    /** Serializes concurrent request-finish folds within this engine process. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * Running aggregate of one outcome bucket: count, total, and the observed extremes.
     *
     * <p> recency: {@link #plus} blends the new sample into a recent-biased average (EWMA
     * alpha {@value #RECENCY_ALPHA}) so multi-year history does not dominate. {@link #avgMillis}
     * returns that blended mean; min/max still track absolute extremes for clamp logic.
     */
    public record Stats(long count, long totalMillis, long minMillis, long maxMillis) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0);

        /** Same alpha as {@link StepTimings#DEFAULT_ALPHA} — recent-weighted, outlier-smoothed. */
        public static final double RECENCY_ALPHA = 0.4;

        /** Recent-biased mean duration, or 0 when nothing has been recorded. */
        public long avgMillis() {
            return count == 0 ? 0 : totalMillis / count;
        }

        /**
         * Fold one more sample. After the first sample, the running mean is updated as {@code
         * α·new + (1−α)·oldAvg} and re-encoded as {@code totalMillis = mean × count} so existing
         * callers of {@link #avgMillis}/{@link #totalMillis} keep working. Count is capped so old
         * volume does not overweight clamp confidence forever.
         */
        Stats plus(long millis) {
            long m = Math.max(0, millis);
            if (count == 0) return new Stats(1, m, m, m);
            long oldAvg = avgMillis();
            long blended = Math.round(RECENCY_ALPHA * m + (1.0 - RECENCY_ALPHA) * oldAvg);
            long newCount = Math.min(count + 1, 20);
            return new Stats(newCount, blended * newCount, Math.min(minMillis, m), Math.max(maxMillis, m));
        }
    }

    /**
     * One aggregate row. Invocation rows carry {@code kind} ({@code build}/{@code test}) and a null
     * {@code step}; step rows carry {@code step} and a null {@code kind}. {@code dir} is the
     * primary project key ({@code ""} = the global tier); {@code coord} is a display label only.
     */
    public record Entry(
            String kind,
            String dir,
            String coord,
            String step,
            Stats ok,
            Stats failed,
            Stats cancelled,
            long updatedMillis) {}

    /** One step's outcome within a finished run; {@code status} is a {@code TaskStatus} name. */
    public record StepSample(String dir, String step, String status, long millis) {}

    /** What the engine maps a finished build record into — the store's only input shape. */
    public record Outcome(
            String kind,
            String dir,
            String coord,
            boolean success,
            boolean cancelled,
            long millis,
            List<StepSample> steps) {
        public Outcome {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** key = kind + SEP + dir (dir "" = global). */
    private final Map<String, Entry> invocations;

    /** key = dir + SEP + step (dir "" = global). */
    private final Map<String, Entry> steps;

    private BuildMetrics(Map<String, Entry> invocations, Map<String, Entry> steps) {
        this.invocations = invocations;
        this.steps = steps;
    }

    /**
     * Legacy path marker — production ETA hydrates from harvested project/host metrics (JK-1377).
     * Hermetic tests still pass isolated temp files to {@link #load}/{@link #record}.
     */
    public static Path defaultFile() {
        return JkDirs.builds().resolve("metrics.json");
    }

    /** Read-only store for {@code file}, memoized for the process. Missing/unreadable → empty. */
    public static BuildMetrics load(Path file) {
        if (file != null && isDefaultMetricsPath(file)) {
            // Prefer harvested aggregates; do not read legacy metrics.json.
            return fromAggregates();
        }
        return MEMO.computeIfAbsent(file, BuildMetrics::read);
    }

    private static boolean isDefaultMetricsPath(Path file) {
        try {
            return file.toAbsolutePath()
                            .normalize()
                            .equals(defaultFile().toAbsolutePath().normalize())
                    || "metrics.json".equals(file.getFileName().toString())
                            && file.getParent() != null
                            && file.getParent().equals(JkDirs.builds());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hydrate invocation/step stats from {@code project-metrics.toml} / {@code host-metrics.toml}. */
    static BuildMetrics fromAggregates() {
        cc.jumpkick.builds.AggregatedMetrics agg = cc.jumpkick.builds.AggregatedMetrics.loadAll(JkDirs.builds());
        Map<String, Entry> inv = new LinkedHashMap<>();
        Map<String, Entry> steps = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (var e : agg.meanMap().entrySet()) {
            String key = e.getKey();
            double ms = e.getValue();
            if (!(ms > 0)) continue;
            long count = Math.max(1, agg.count(key));
            long avg = Math.round(ms);
            Stats ok = new Stats(count, avg * count, avg, avg);
            Double last = agg.lastMap().get(key);
            if (last != null && last > 0) {
                long l = Math.round(last);
                ok = new Stats(count, avg * count, Math.min(avg, l), Math.max(avg, l));
            }
            if (key.startsWith("invocation.") && key.endsWith(".wall-ms")) {
                // invocation.<kind>[.<dirKey>].wall-ms
                String body = key.substring("invocation.".length(), key.length() - ".wall-ms".length());
                int dot = body.indexOf('.');
                String kind = dot < 0 ? body : body.substring(0, dot);
                String dir = dot < 0 ? "" : body.substring(dot + 1);
                inv.put(kind + SEP + dir, new Entry(kind, dir, null, null, ok, Stats.EMPTY, Stats.EMPTY, now));
            } else if (key.equals("workspace.wall-ms")) {
                inv.putIfAbsent(
                        "build" + SEP + "", new Entry("build", "", null, null, ok, Stats.EMPTY, Stats.EMPTY, now));
            } else if (key.startsWith("module.") && key.contains(".step.") && key.endsWith(".wall-ms")) {
                // module.<dir>.step.<step>.wall-ms
                String body = key.substring("module.".length(), key.length() - ".wall-ms".length());
                int stepAt = body.indexOf(".step.");
                if (stepAt > 0) {
                    String dir = body.substring(0, stepAt);
                    String step = body.substring(stepAt + ".step.".length());
                    steps.put(dir + SEP + step, new Entry(null, dir, null, step, ok, Stats.EMPTY, Stats.EMPTY, now));
                }
            } else if (key.startsWith("step.") && key.endsWith(".wall-ms") && !key.contains("module.")) {
                String step = key.substring("step.".length(), key.length() - ".wall-ms".length());
                steps.put("" + SEP + step, new Entry(null, "", null, step, ok, Stats.EMPTY, Stats.EMPTY, now));
            }
        }
        return new BuildMetrics(inv, steps);
    }

    /** True when nothing has been recorded yet. */
    public boolean isEmpty() {
        return invocations.isEmpty() && steps.isEmpty();
    }

    /** The invocation aggregate for {@code (kind, dir)}; {@code dir ""} = the global tier. */
    public Optional<Entry> invocation(String kind, String dir) {
        return Optional.ofNullable(invocations.get(kind + SEP + dir));
    }

    /**
     * Merged OK stats for {@code kind} across every dirty-count shape of {@code dir}
     * ({@code dir} itself plus {@code dir#dN} rows). shaped the write side, which made
     * exact bare-path lookups read a key that is never written.
     */
    public Stats okAcrossShapes(String kind, String dir) {
        long count = 0, total = 0, min = Long.MAX_VALUE, max = 0;
        for (Entry e : invocations.values()) {
            if (!kind.equals(e.kind()) || !sameBaseDir(dir, e.dir())) continue;
            Stats ok = e.ok();
            if (ok.count() == 0) continue;
            count += ok.count();
            total += ok.totalMillis();
            min = Math.min(min, ok.minMillis());
            max = Math.max(max, ok.maxMillis());
        }
        return count == 0 ? Stats.EMPTY : new Stats(count, total, min, max);
    }

    /** True when {@code candidate} is {@code dir} or a {@code dir#dN} shape of it. */
    public static boolean sameBaseDir(String dir, String candidate) {
        return dir.equals(candidate) || dir.equals(baseDir(candidate));
    }

    /** Strip a trailing {@code #dN} shape suffix{@code path#d3} → {@code path}. */
    public static String baseDir(String dir) {
        if (dir == null) return "";
        int i = dir.lastIndexOf("#d");
        if (i <= 0) return dir;
        for (int j = i + 2; j < dir.length(); j++) {
            if (!Character.isDigit(dir.charAt(j))) return dir;
        }
        return i + 2 == dir.length() ? dir : dir.substring(0, i);
    }

    /**
     * Total finished runs recorded for {@code dir} (all kinds, including {@code #dN} shapes). Used by
     * {@link BuildNumberAllocator} so start-time numbers continue past historical metrics.
     */
    public long projectRunCount(String dir) {
        return projectRunCount(invocations, dir);
    }

    /** The step aggregate for {@code (dir, step)}; {@code dir ""} = the global tier. */
    public Optional<Entry> step(String dir, String step) {
        return Optional.ofNullable(steps.get(dir + SEP + step));
    }

    /** Every row, stable-ordered: invocation rows by (kind, dir), then step rows by (dir, step). */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(invocations.size() + steps.size());
        invocations.values().stream()
                .sorted(Comparator.comparing(Entry::kind).thenComparing(Entry::dir))
                .forEach(out::add);
        steps.values().stream()
                .sorted(Comparator.comparing(Entry::dir).thenComparing(Entry::step))
                .forEach(out::add);
        return out;
    }

    /**
     * Fold one finished run into the on-disk store — a single locked load-update-write at
     * request-finish, stamping every touched row with {@code nowMillis}. Updates the project tier
     * and the global tier together; step samples with status {@code SKIPPED} (or any non-terminal
     * status) teach nothing and are ignored. Best-effort: any failure is swallowed.
     *
     * <p>When {@code assignedBuildNumber} is positive (allocated at request-start by
     * {@link BuildNumberAllocator}), that value is returned for the journal — finish must not mint a
     * second number. Otherwise falls back to the post-fold project run count (legacy).
     *
     * @return this run's <strong>build number</strong>, or {@code 0} when nothing was recorded.
     */
    public static long record(Path file, Outcome o, long nowMillis) {
        return record(file, o, nowMillis, 0L);
    }

    /** As {@link #record(Path, Outcome, long)} with a start-time assigned build number. */
    public static long record(Path file, Outcome o, long nowMillis, long assignedBuildNumber) {
        if (o == null || o.kind() == null || o.dir() == null || o.dir().isEmpty()) return 0;
        // Production path: per-run metrics.toml + MetricsHarvest own durable aggregates (JK-1377).
        if (file != null && isDefaultMetricsPath(file)) {
            return assignedBuildNumber > 0 ? assignedBuildNumber : 0;
        }
        LOCK.lock();
        try {
            BuildMetrics cur = read(file);
            Map<String, Entry> inv = new LinkedHashMap<>(cur.invocations);
            Map<String, Entry> ph = new LinkedHashMap<>(cur.steps);

            foldInvocation(inv, o.kind(), o.dir(), o.coord(), o, nowMillis);
            foldInvocation(inv, o.kind(), "", null, o, nowMillis);
            // Cancelled workspaces must not train step/module averages: a mid-run kill leaves
            // SUCCESS steps with truncated walls that poison ETA / estimator hygiene).
            // Only fully-successful workspaces teach per-step `ok` stats (the guard two lines
            // down); failed-but-complete runs teach only their failure buckets, for diagnostics
            // ; see docs/perf/progress-contract.md "Success-only teaching").
            if (!o.cancelled()) {
                for (StepSample s : o.steps()) {
                    if (s.step() == null || s.step().isEmpty()) continue;
                    String bucket = bucketOf(s.status());
                    if (bucket == null) continue;
                    // Success-only teaching for ok; failures stay in their bucket for diagnostics.
                    if ("ok".equals(bucket) && !o.success()) continue;
                    foldStep(ph, s.dir() == null ? o.dir() : s.dir(), s.step(), bucket, s.millis(), nowMillis);
                    foldStep(ph, "", s.step(), bucket, s.millis(), nowMillis);
                }
            }

            write(file, inv, ph);
            MEMO.remove(file); // next load in this process sees the update
            if (assignedBuildNumber > 0) return assignedBuildNumber;
            return projectRunCount(inv, o.dir());
        } catch (IOException | RuntimeException ignored) {
            // advisory state — never fail the build over it
            return assignedBuildNumber > 0 ? assignedBuildNumber : 0;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The project's build number: total runs recorded for {@code dir} across every kind after the
     * current fold. Monotonic for a live project — its rows are refreshed on each build, so age
     * eviction spares them — which is what makes it a stable per-project sequence.
     */
    private static long projectRunCount(Map<String, Entry> inv, String dir) {
        // Shaped keys (path#dN) all belong to ONE project: fold them together or the
        // documented monotonic per-project sequence forks per dirty-count.
        String base = baseDir(dir);
        long n = 0;
        for (Entry e : inv.values()) {
            if (sameBaseDir(base, e.dir()))
                n += e.ok().count() + e.failed().count() + e.cancelled().count();
        }
        return n;
    }

    private static void foldInvocation(
            Map<String, Entry> inv, String kind, String dir, String coord, Outcome o, long nowMillis) {
        String k = kind + SEP + dir;
        Entry e = inv.getOrDefault(
                k, new Entry(kind, dir, coord, null, Stats.EMPTY, Stats.EMPTY, Stats.EMPTY, nowMillis));
        Stats ok = e.ok(), failed = e.failed(), cancelled = e.cancelled();
        // Cancelled wins over success: a truncated Ctrl-C wall must never train the ok bucket that
        // ETA priors read (even if a racy outcome reported success). Failed stays separate.
        if (o.cancelled()) cancelled = cancelled.plus(o.millis());
        else if (o.success()) ok = ok.plus(o.millis());
        else failed = failed.plus(o.millis());
        // A freshly-learned coord upgrades a row that predates one (label only, never a key).
        String label = coord != null ? coord : e.coord();
        inv.put(k, new Entry(kind, dir, label, null, ok, failed, cancelled, nowMillis));
    }

    private static void foldStep(
            Map<String, Entry> ph, String dir, String step, String bucket, long millis, long nowMillis) {
        String k = dir + SEP + step;
        Entry e =
                ph.getOrDefault(k, new Entry(null, dir, null, step, Stats.EMPTY, Stats.EMPTY, Stats.EMPTY, nowMillis));
        Stats ok = e.ok(), failed = e.failed(), cancelled = e.cancelled();
        switch (bucket) {
            case "ok" -> ok = ok.plus(millis);
            case "failed" -> failed = failed.plus(millis);
            default -> cancelled = cancelled.plus(millis);
        }
        ph.put(k, new Entry(null, dir, null, step, ok, failed, cancelled, nowMillis));
    }

    /** Maps a {@code TaskStatus} name to a stats bucket; null = don't record (SKIPPED, non-terminal). */
    private static String bucketOf(String status) {
        if (status == null) return null;
        return switch (status) {
            case "SUCCESS" -> "ok";
            case "FAIL" -> "failed";
            case "CANCELLED" -> "cancelled";
            default -> null;
        };
    }

    // --- GC -----------------------------------------------------------------

    /** Bounds for {@link #prune}: a byte ceiling and a max row age. */
    public record Limits(long maxBytes, long maxAgeMillis) {
        static final long DEFAULT_MAX_MB = 10;
        static final long DEFAULT_MAX_AGE_DAYS = 730; // 2 years

        /**
         * Resolve from {@code JK_METRICS_MAX_SIZE_MB} / {@code JK_METRICS_MAX_AGE_DAYS} env vars,
         * else the {@code [metrics] max-size-mb} / {@code max-age-days} keys in the user config,
         * else the defaults (10 MB / 2 years).
         */
        public static Limits resolve(Path userConfig, Function<String, String> env) {
            long mb = envLong(env, "JK_METRICS_MAX_SIZE_MB")
                    .orElseGet(() -> tomlLong(userConfig, "max-size-mb").orElse(DEFAULT_MAX_MB));
            long days = envLong(env, "JK_METRICS_MAX_AGE_DAYS")
                    .orElseGet(() -> tomlLong(userConfig, "max-age-days").orElse(DEFAULT_MAX_AGE_DAYS));
            return new Limits(Math.max(0, mb) * 1024L * 1024L, Math.max(0, days) * 86_400_000L);
        }
    }

    /** What a {@link #prune} pass evicted. */
    public record PruneReport(int evictedByAge, int evictedBySize, int kept, long finalBytes) {
        static final PruneReport EMPTY = new PruneReport(0, 0, 0, 0);
    }

    /**
     * Evict stale/overflowing rows: first anything older than {@code maxAge}, then — if the
     * rendered file still exceeds {@code maxBytes} — the oldest rows until it fits. Global rows are
     * refreshed by every build, so age eviction naturally spares them while a project you stopped
     * building ages out. Rewrites the file unless {@code dryRun}.
     */
    public static PruneReport prune(Path file, Limits limits, long nowMillis, boolean dryRun) {
        if (!Files.isRegularFile(file)) return PruneReport.EMPTY;
        LOCK.lock();
        try {
            BuildMetrics cur = read(file);
            Map<String, Entry> inv = new LinkedHashMap<>(cur.invocations);
            Map<String, Entry> ph = new LinkedHashMap<>(cur.steps);

            int byAge = 0;
            if (limits.maxAgeMillis() > 0) {
                byAge += evictOlderThan(inv, nowMillis - limits.maxAgeMillis());
                byAge += evictOlderThan(ph, nowMillis - limits.maxAgeMillis());
            }

            int bySize = 0;
            long bytes = renderedBytes(inv, ph);
            int rows = inv.size() + ph.size();
            if (limits.maxBytes() > 0 && bytes > limits.maxBytes() && rows > 0) {
                long avg = Math.max(1, bytes / rows);
                int keep = (int) Math.min(rows, Math.max(0, limits.maxBytes() / avg));
                if (keep < rows) {
                    // Drop the oldest (smallest updatedMillis) first, across both families.
                    List<Map.Entry<Long, Runnable>> victims = new ArrayList<>(rows);
                    inv.forEach((k, e) -> victims.add(Map.entry(e.updatedMillis(), () -> inv.remove(k))));
                    ph.forEach((k, e) -> victims.add(Map.entry(e.updatedMillis(), () -> ph.remove(k))));
                    victims.sort(Map.Entry.comparingByKey());
                    for (int i = 0; i < rows - keep; i++) {
                        victims.get(i).getValue().run();
                        bySize++;
                    }
                }
            }

            if ((byAge > 0 || bySize > 0) && !dryRun) {
                try {
                    if (inv.isEmpty() && ph.isEmpty()) Files.deleteIfExists(file);
                    else write(file, inv, ph);
                    MEMO.remove(file);
                } catch (IOException | RuntimeException ignored) {
                    // advisory — leave the file as-is on failure
                }
            }
            return new PruneReport(byAge, bySize, inv.size() + ph.size(), renderedBytes(inv, ph));
        } finally {
            LOCK.unlock();
        }
    }

    private static int evictOlderThan(Map<String, Entry> m, long cutoffMillis) {
        int evicted = 0;
        var it = m.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.updatedMillis() > 0 && e.updatedMillis() < cutoffMillis) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }

    // --- IO -----------------------------------------------------------------

    private static BuildMetrics read(Path file) {
        Map<String, Entry> inv = new LinkedHashMap<>();
        Map<String, Entry> ph = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file) && MiniJson.parse(Files.readString(file)) instanceof Map<?, ?> root) {
                for (Object row : list(root.get("invocations"))) {
                    Entry e = readEntry(row, true);
                    if (e != null) inv.put(e.kind() + SEP + e.dir(), e);
                }
                for (Object row : list(root.get("steps"))) {
                    Entry e = readEntry(row, false);
                    if (e != null) ph.put(e.dir() + SEP + e.step(), e);
                }
            }
        } catch (Exception ignored) {
            // unreadable/corrupt store → treat as empty
        }
        return new BuildMetrics(inv, ph);
    }

    private static Entry readEntry(Object row, boolean invocation) {
        if (!(row instanceof Map<?, ?> o)) return null;
        String kind = str(o.get("kind"));
        String dir = str(o.get("dir"));
        String step = str(o.get("step"));
        if (dir == null || (invocation ? kind == null : step == null)) return null;
        return new Entry(
                invocation ? kind : null,
                dir,
                str(o.get("coord")),
                invocation ? null : step,
                stats(o.get("ok")),
                stats(o.get("failed")),
                stats(o.get("cancelled")),
                lng(o.get("updated")));
    }

    private static Stats stats(Object v) {
        if (!(v instanceof Map<?, ?> o)) return Stats.EMPTY;
        return new Stats(
                lng(o.get("count")), lng(o.get("totalMillis")), lng(o.get("minMillis")), lng(o.get("maxMillis")));
    }

    private static Map<String, Object> render(Map<String, Entry> inv, Map<String, Entry> ph) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", (long) SCHEMA);
        List<Object> invRows = new ArrayList<>(inv.size());
        new TreeMap<>(inv).values().forEach(e -> invRows.add(renderEntry(e)));
        root.put("invocations", invRows);
        List<Object> phRows = new ArrayList<>(ph.size());
        new TreeMap<>(ph).values().forEach(e -> phRows.add(renderEntry(e)));
        root.put("steps", phRows);
        return root;
    }

    private static Map<String, Object> renderEntry(Entry e) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (e.kind() != null) o.put("kind", e.kind());
        o.put("dir", e.dir());
        if (e.coord() != null) o.put("coord", e.coord());
        if (e.step() != null) o.put("step", e.step());
        o.put("ok", renderStats(e.ok()));
        o.put("failed", renderStats(e.failed()));
        o.put("cancelled", renderStats(e.cancelled()));
        o.put("updated", e.updatedMillis());
        return o;
    }

    private static Map<String, Object> renderStats(Stats s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("count", s.count());
        o.put("totalMillis", s.totalMillis());
        o.put("minMillis", s.minMillis());
        o.put("maxMillis", s.maxMillis());
        return o;
    }

    private static long renderedBytes(Map<String, Entry> inv, Map<String, Entry> ph) {
        return MiniJson.writePretty(render(inv, ph)).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void write(Path file, Map<String, Entry> inv, Map<String, Entry> ph) throws IOException {
        AtomicWrites.replace(file, MiniJson.writePretty(render(inv, ph)));
    }

    private static List<?> list(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static OptionalLong envLong(Function<String, String> env, String name) {
        Optional<Long> v = EnvValues.longValue(env, name);
        return v.isPresent() ? OptionalLong.of(v.get()) : OptionalLong.empty();
    }

    private static OptionalLong tomlLong(Path userConfig, String key) {
        // The [metrics] table of the user-global ~/.config/jk/config.toml; missing/malformed → empty.
        Optional<Long> v = TomlValues.parse(userConfig)
                .map(toml -> toml.getTable("metrics"))
                .flatMap(t -> TomlValues.optLong(t, key));
        return v.isPresent() ? OptionalLong.of(v.get()) : OptionalLong.empty();
    }

    /** Test seam: drop the per-process load memo so a freshly-written file is re-read. */
    static void clearMemo() {
        MEMO.clear();
    }
}
