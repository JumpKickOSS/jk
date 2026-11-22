// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import java.nio.file.Path;
import java.time.Duration;
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
 * <p>Every known scalar key is listed so the UI can show a full effective document, and every key
 * of the file this jk does not read ({@link UserConfigKeys}) is listed as {@link #UNKNOWN}.
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

    /** The value shown for a key of the file this jk does not read. */
    public static final String UNKNOWN = "unknown key, ignored";

    private EffectiveUserConfig() {}

    /** Path the rows were resolved from (may not exist yet). */
    public static Path configPath() {
        return JkDirs.userConfigFile();
    }

    /**
     * Why {@code userConfig} does not parse, as one line naming the file and the place, or empty
     * when it parses or is absent. The rows of a file that does not parse are their defaults.
     */
    public static Optional<String> parseError(Path userConfig) {
        return GlobalConfig.userConfigProblem(userConfig).map(e -> Objects.toString(e.getMessage(), ""));
    }

    /** {@link #parseError(Path)} of the live machine config. */
    public static Optional<String> parseError() {
        return parseError(JkDirs.userConfigFile());
    }

    /** Effective rows for the live machine config. */
    public static List<Row> rows() {
        return rows(JkDirs.userConfigFile(), BuildEnv::get);
    }

    /** As {@link #rows()} against an explicit config file + env — for tests. */
    public static List<Row> rows(Path userConfig, Function<String, @Nullable String> env) {
        return rows(userConfig, env, () -> JkCacheConfig.DiskSpace.probe(JkDirs.cache()));
    }

    /** Test seam: inject disk space so size defaults are hermetic. */
    static List<Row> rows(
            Path userConfig, Function<String, @Nullable String> env, Supplier<JkCacheConfig.DiskSpace> disk) {
        List<Row> out = new ArrayList<>();
        // A file that does not parse gives the defaults, which is what a lenient reader of it sees.
        boolean parses = GlobalConfig.userConfigProblem(userConfig).isEmpty();
        // A key this jk does not read comes first, so a misspelt or retired key is not mistaken for a default.
        for (String key : UserConfigKeys.unknown(userConfig)) add(out, key, "", UNKNOWN);
        addGlobal(out, userConfig, env);
        addToolchain(out, userConfig, env);
        addHttp(out, userConfig, env);
        addEngine(out, userConfig, env);
        addCache(out, userConfig, env, disk);
        addHistory(out, userConfig, env);
        addM2(out, userConfig, env);
        addInstall(out, userConfig, env);
        addCredentials(out, parses ? userConfig : null, env);
        addTemplates(out, parses ? userConfig : null);
        return List.copyOf(out);
    }

    private static void addGlobal(List<Row> out, Path file, Function<String, @Nullable String> env) {
        // The declared mode, not the detected caps: this table reports configuration intent, so
        // "auto" must read as "auto" rather than as whatever this terminal happens to resolve to.
        NerdFontMode mode = GlobalConfig.nerdFontMode(file, env.apply("JK_NERD_FONT"), env.apply("NERD_FONT"));
        add(out, GlobalConfig.NERD_FONT.dotted(), NerdFontMode.AUTO.name().toLowerCase(Locale.ROOT), name(mode));
        String selfUpdate = SelfUpdatePolicy.Mode.parse(env.apply(SelfUpdatePolicy.ENV))
                .or(() -> GlobalConfig.selfUpdate(file).flatMap(SelfUpdatePolicy.Mode::parse))
                .map(SelfUpdatePolicy.Mode::spelling)
                .orElse("");
        add(out, SelfUpdatePolicy.KEY, "", selfUpdate);
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
        add(out, GlobalConfig.TOOLCHAIN_JDK.dotted(), "", pin);
    }

    private static void addHttp(List<Row> out, Path file, Function<String, @Nullable String> env) {
        Optional<JkHttpConfig> resolved = JkHttpConfig.resolve(file, env);
        JkHttpConfig d = JkHttpConfig.DEFAULTS;
        add(out, JkHttpConfig.HTTP_ENABLED_KEY.dotted(), true, resolved.isPresent());
        JkHttpConfig e = resolved.orElse(d);
        add(out, JkHttpConfig.HOST_KEY.dotted(), d.host(), e.host());
        add(out, JkHttpConfig.PORT_KEY.dotted(), d.port(), e.port());
        add(
                out,
                JkHttpConfig.MAX_CONCURRENT_REQUESTS_KEY.dotted(),
                d.maxConcurrentRequests(),
                e.maxConcurrentRequests());
        add(out, JkHttpConfig.MAX_EVENT_STREAMS_KEY.dotted(), d.maxEventStreams(), e.maxEventStreams());
        add(out, JkHttpConfig.WEB_ROOT_KEY.dotted(), d.webRoot(), e.webRoot());
        add(out, JkHttpConfig.WEB_OVERLAY_KEY.dotted(), d.webOverlay(), e.webOverlay());
        add(
                out,
                JkHttpConfig.MCP_ENABLED_KEY.dotted(),
                d.mcp().enabled(),
                e.mcp().enabled());
        add(
                out,
                JkHttpConfig.MCP_MAX_EVENT_STREAMS_KEY.dotted(),
                d.mcp().maxEventStreams(),
                e.mcp().maxEventStreams());
        add(
                out,
                JkHttpConfig.MCP_MAX_SESSIONS_KEY.dotted(),
                d.mcp().maxSessions(),
                e.mcp().maxSessions());
        add(
                out,
                SessionExpiry.KEY.dotted(),
                SessionExpiry.spell(SessionExpiry.DEFAULT),
                SessionExpiry.spell(SessionExpiry.resolve(file, env)));
        add(out, JkHttpConfig.MCP_TOOLS_KEY.dotted(), d.mcp().tools(), e.mcp().tools());
    }

    private static void addEngine(List<Row> out, Path file, Function<String, @Nullable String> env) {
        // Heap default is CI-aware (256 MiB locally, 512 MiB when CI=1|true).
        JkEngineConfig d = JkEngineConfig.resolvedDefaults(env);
        JkEngineConfig e = JkEngineConfig.resolve(file, env);
        add(out, EngineControls.MAX_HEAP_MB.dotted(), d.maxHeapMb(), e.maxHeapMb());
        add(out, EngineControls.JOBS.dotted(), jobsLabel(d.jobs()), jobsLabel(e.jobs()));
        add(out, EngineControls.CONTINUE.dotted(), d.keepGoing(), e.keepGoing());
        add(out, EngineControls.VFS_MAX_MB.dotted(), d.vfsMaxMb(), e.vfsMaxMb());
        add(out, EngineControls.AUTO_WARMUP.dotted(), d.autoWarmup(), e.autoWarmup());
        add(out, EngineControls.LOG_MAX_MB.dotted(), d.logMaxMb(), e.logMaxMb());
        add(out, EngineControls.LOG_LEVEL.dotted(), d.logLevel(), e.logLevel());
        add(
                out,
                EngineControls.KEEP_AWAKE.dotted(),
                d.keepAwake().spelling(),
                e.keepAwake().spelling());
        add(
                out,
                EngineControls.DETACHED_DEADLINE_MS.dotted(),
                d.jobLimits().detachedDeadlineMs(),
                e.jobLimits().detachedDeadlineMs());
        add(
                out,
                EngineControls.QUEUE_WAIT_MS.dotted(),
                d.jobLimits().queueWaitMs(),
                e.jobLimits().queueWaitMs());
        add(
                out,
                EngineControls.DRAIN_DEADLINE_MS.dotted(),
                d.jobLimits().drainDeadlineMs(),
                e.jobLimits().drainDeadlineMs());
    }

    private static void addCache(
            List<Row> out, Path file, Function<String, @Nullable String> env, Supplier<JkCacheConfig.DiskSpace> disk) {
        // Defaults are machine-aware (CI + small-disk clamp) so "overridden" is real user intent.
        JkCacheConfig.DiskSpace space = disk != null ? disk.get() : null;
        JkCacheConfig d = JkCacheConfig.resolvedDefaults(env, space);
        JkCacheConfig e = JkCacheConfig.resolve(file, env, space);
        add(out, JkCacheConfig.AUTO_PRUNE_KEY, d.autoPrune(), e.autoPrune());
        add(
                out,
                JkCacheConfig.MAX_CACHE_SIZE_GB_KEY,
                JkCacheConfig.formatGb(d.maxCacheSizeGb()),
                JkCacheConfig.formatGb(e.maxCacheSizeGb()));
        add(out, JkCacheConfig.PRUNE_INTERVAL_DAYS_KEY, d.pruneIntervalDays(), e.pruneIntervalDays());
        // Off on CI by default; the CI-aware default makes "overridden" mean the user set it.
        Duration heavyDefault = EnvValues.isCi(env) ? Duration.ZERO : HeavyTtl.DEFAULT;
        add(
                out,
                HeavyTtl.KEY.dotted(),
                SessionExpiry.spell(heavyDefault),
                SessionExpiry.spell(HeavyTtl.resolve(file, env)));
    }

    private static void addHistory(List<Row> out, Path file, Function<String, @Nullable String> env) {
        JkHistoryConfig d = JkHistoryConfig.DEFAULTS;
        JkHistoryConfig e = JkHistoryConfig.resolve(file, env);
        add(out, JkHistoryConfig.ENABLED_KEY.dotted(), d.enabled(), e.enabled());
        add(out, JkHistoryConfig.MAX_AGE_DAYS_KEY.dotted(), d.maxAgeDays(), e.maxAgeDays());
        add(out, JkHistoryConfig.MAX_DISK_MB_KEY.dotted(), d.maxDiskMb(), e.maxDiskMb());
    }

    private static void addM2(List<Row> out, Path file, Function<String, @Nullable String> env) {
        JkM2Config d = JkM2Config.DEFAULTS;
        JkM2Config e = JkM2Config.resolve(file, env);
        add(out, JkM2Config.INTEGRATION_KEY, d.mode().spelling(), e.mode().spelling());
        add(out, JkM2Config.AUTO_CLEAN_KEY, d.autoClean(), e.autoClean());
        add(out, JkM2Config.CLEAN_AFTER_DAYS_KEY, d.cleanAfterDays(), e.cleanAfterDays());
    }

    private static void addInstall(List<Row> out, Path file, Function<String, @Nullable String> env) {
        JkInstallConfig d = JkInstallConfig.DEFAULTS;
        JkInstallConfig e = JkInstallConfig.resolve(file, env);
        add(
                out,
                JkInstallConfig.DESTINATION_KEY,
                d.destination().spelling(),
                e.destination().spelling());
    }

    /**
     * {@code [credentials] store}, under {@code JK_CREDENTIALS_STORE}, as spelled; validated where a
     * login reads it. {@code file} is null when it does not parse, and the row shows the default.
     */
    private static void addCredentials(List<Row> out, @Nullable Path file, Function<String, @Nullable String> env) {
        String fromEnv = env.apply("JK_CREDENTIALS_STORE");
        String store = fromEnv != null && !fromEnv.isBlank()
                ? fromEnv.strip()
                : (file == null ? Optional.<String>empty() : GlobalConfig.credentialsStore(file)).orElse("auto");
        add(out, GlobalConfig.CREDENTIALS_STORE.dotted(), "auto", store);
    }

    /** The {@code [templates]} rows; {@code file} is null when it does not parse, and the effective values are the defaults. */
    private static void addTemplates(List<Row> out, @Nullable Path file) {
        JkTemplatesConfig d = JkTemplatesConfig.defaults();
        JkTemplatesConfig e = file == null ? d : JkTemplatesConfig.resolve(file);
        add(out, JkTemplatesConfig.OFFICIAL_KEY.dotted(), d.officialUrl(), e.officialUrl());
        add(
                out,
                String.join(".", JkTemplatesConfig.SOURCES.path()),
                sourcesLabel(d.sources()),
                sourcesLabel(e.sources()));
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
