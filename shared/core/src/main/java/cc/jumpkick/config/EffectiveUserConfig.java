// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Flattened view of the machine-scoped {@code ~/.jk/config.toml} (plus env) as key /
 * default / effective triples for the dashboard Configuration panel.
 *
 * <p>Every known scalar key is listed so the UI can show a full effective document — not only the
 * three knobs historically embedded in {@code GET /api/status}.
 */
public final class EffectiveUserConfig {

    /**
     * One config key. {@link #overridden()} is true when the effective value differs from the
     * documented default (file or env changed it).
     */
    public record Row(String key, String defaultValue, String effectiveValue) {
        public Row {
            Objects.requireNonNull(key, "key");
            defaultValue = defaultValue == null ? "" : defaultValue;
            effectiveValue = effectiveValue == null ? "" : effectiveValue;
        }

        public boolean overridden() {
            return !defaultValue.equals(effectiveValue);
        }
    }

    private EffectiveUserConfig() {}

    /** Path the rows were resolved from (may not exist yet). */
    public static Path configPath() {
        return JkDirs.userConfigFile();
    }

    /** Effective rows for the live machine config. */
    public static List<Row> rows() {
        return rows(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #rows()} against an explicit config file + env — for tests. */
    public static List<Row> rows(Path userConfig, Function<String, @Nullable String> env) {
        return rows(userConfig, env, () -> JkCacheConfig.DiskSpace.probe(JkDirs.cache()));
    }

    /** Test seam: inject disk space so size defaults are hermetic. */
    static List<Row> rows(
            Path userConfig, Function<String, @Nullable String> env, Supplier<JkCacheConfig.DiskSpace> disk) {
        List<Row> out = new ArrayList<>();
        addGlobal(out, userConfig, env);
        addToolchain(out, userConfig, env);
        addHttp(out, userConfig, env);
        addEngine(out, userConfig, env);
        addCache(out, userConfig, env, disk);
        addHistory(out, userConfig, env);
        addM2(out, userConfig, env);
        addTemplates(out, userConfig);
        return List.copyOf(out);
    }

    private static void addGlobal(List<Row> out, Path file, Function<String, @Nullable String> env) {
        // The declared mode, not the detected caps: this table reports configuration intent, so
        // "auto" must read as "auto" rather than as whatever this terminal happens to resolve to.
        NerdFontMode mode = GlobalConfig.nerdFontMode(file, env.apply("JK_NERD_FONT"), env.apply("NERD_FONT"));
        add(out, "nerd-font", NerdFontMode.AUTO.name().toLowerCase(Locale.ROOT), name(mode));
    }

    /** Lowercase mode word as it is spelled in config — {@code false} / {@code true} for the booleans. */
    private static String name(NerdFontMode mode) {
        return switch (mode) {
            case OFF -> "false";
            case ON -> "true";
            default -> mode.name().toLowerCase(Locale.ROOT);
        };
    }

    private static void addToolchain(List<Row> out, Path file, Function<String, @Nullable String> env) {
        String pin = GlobalConfig.engineJdkPin(file, env.apply("JK_ENGINE_JDK")).orElse("");
        add(out, "toolchain.jdk", "", pin);
    }

    private static void addHttp(List<Row> out, Path file, Function<String, @Nullable String> env) {
        Optional<JkHttpConfig> resolved = JkHttpConfig.resolve(file, env);
        JkHttpConfig d = JkHttpConfig.DEFAULTS;
        add(out, "http.enabled", true, resolved.isPresent());
        JkHttpConfig e = resolved.orElse(d);
        add(out, "http.host", d.host(), e.host());
        add(out, "http.port", d.port(), e.port());
        add(out, "http.max-concurrent-requests", d.maxConcurrentRequests(), e.maxConcurrentRequests());
        add(out, "http.max-event-streams", d.maxEventStreams(), e.maxEventStreams());
        add(out, "http.web-root", d.webRoot(), e.webRoot());
        add(out, "mcp.enabled", d.mcp().enabled(), e.mcp().enabled());
        add(out, "mcp.max-event-streams", d.mcp().maxEventStreams(), e.mcp().maxEventStreams());
    }

    private static void addEngine(List<Row> out, Path file, Function<String, @Nullable String> env) {
        // Heap default is CI-aware (256 MiB locally, 512 MiB when CI=1|true).
        JkEngineConfig d = JkEngineConfig.resolvedDefaults(env);
        JkEngineConfig e = JkEngineConfig.resolve(file, env);
        add(out, "engine.max-heap-mb", d.maxHeapMb(), e.maxHeapMb());
        add(out, "engine.jobs", jobsLabel(d.jobs()), jobsLabel(e.jobs()));
        add(out, "engine.continue", d.keepGoing(), e.keepGoing());
        add(out, "engine.vfs-max-mb", d.vfsMaxMb(), e.vfsMaxMb());
        add(out, "engine.auto-warmup", d.autoWarmup(), e.autoWarmup());
        add(out, "engine.log-max-mb", d.logMaxMb(), e.logMaxMb());
        add(out, "engine.log-level", d.logLevel(), e.logLevel());
        add(
                out,
                "engine.detached-deadline-ms",
                d.jobLimits().detachedDeadlineMs(),
                e.jobLimits().detachedDeadlineMs());
    }

    private static void addCache(
            List<Row> out, Path file, Function<String, @Nullable String> env, Supplier<JkCacheConfig.DiskSpace> disk) {
        // Defaults are machine-aware (CI + small-disk clamp) so "overridden" is real user intent.
        JkCacheConfig.DiskSpace space = disk != null ? disk.get() : null;
        JkCacheConfig d = JkCacheConfig.resolvedDefaults(env, space);
        JkCacheConfig e = JkCacheConfig.resolve(file, env, space);
        add(out, "cache.auto-prune", d.autoPrune(), e.autoPrune());
        add(
                out,
                "cache.max-cache-size-gb",
                JkCacheConfig.formatGb(d.maxCacheSizeGb()),
                JkCacheConfig.formatGb(e.maxCacheSizeGb()));
        add(out, "cache.prune-interval-days", d.pruneIntervalDays(), e.pruneIntervalDays());
    }

    private static void addHistory(List<Row> out, Path file, Function<String, @Nullable String> env) {
        JkHistoryConfig d = JkHistoryConfig.DEFAULTS;
        JkHistoryConfig e = JkHistoryConfig.resolve(file, env);
        add(out, "history.enabled", d.enabled(), e.enabled());
        add(out, "history.max-age-days", d.maxAgeDays(), e.maxAgeDays());
        add(out, "history.max-disk-mb", d.maxDiskMb(), e.maxDiskMb());
    }

    private static void addM2(List<Row> out, Path file, Function<String, @Nullable String> env) {
        JkM2Config d = JkM2Config.DEFAULTS;
        JkM2Config e = JkM2Config.resolve(file, env);
        add(out, "m2.integration", d.integration(), e.integration());
        add(out, "m2.install", d.install(), e.install());
    }

    private static void addTemplates(List<Row> out, Path file) {
        JkTemplatesConfig d = JkTemplatesConfig.defaults();
        JkTemplatesConfig e = JkTemplatesConfig.resolve(file);
        add(out, "templates.official", d.officialUrl(), e.officialUrl());
        add(out, "templates.sources", sourcesLabel(d.sources()), sourcesLabel(e.sources()));
    }

    private static String jobsLabel(@Nullable Integer jobs) {
        return jobs == null ? "auto" : Integer.toString(jobs);
    }

    private static String sourcesLabel(List<JkTemplatesConfig.Source> sources) {
        if (sources == null || sources.isEmpty()) return "0";
        return Integer.toString(sources.size());
    }

    private static void add(List<Row> out, String key, Object def, Object effective) {
        out.add(new Row(key, stringify(def), stringify(effective)));
    }

    private static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(v);
    }
}
