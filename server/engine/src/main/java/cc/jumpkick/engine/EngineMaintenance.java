// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One-minute resident-engine chore loop (laptop-safe 12 h maintenance).
 *
 * <ul>
 *   <li>Reload user {@code config.toml} when its mtime changes (best-effort; heap size is not
 *       re-applied to the running JVM).
 *   <li>If wall-clock ≥ 12 h since the last maintenance stamp, run store-feed refresh + templates
 *       freshen + enqueue cache prune / host warmup (AOT/cal). Heap {@code System.gc()} is
 *       performed only at the end of that workset by the engine (never mid-chore).
 * </ul>
 *
 * <p>Does not sleep 12 h continuously — a suspended laptop still sees a due cycle on the next
 * minute tick after resume.
 */
public final class EngineMaintenance implements AutoCloseable {

    /** How often we poll config mtime and the 12 h stamp. */
    public static final Duration TICK = Duration.ofMinutes(1);

    /** Wall-clock gap between full maintenance cycles (feeds, templates, GC, warmup). */
    public static final Duration MAINTENANCE_INTERVAL = StoreFeedRefresh.INTERVAL;

    private final Consumer<String> log;
    private final StoreFeedRefresh feeds;
    private final Runnable onMaintenanceDue;
    private final Path stampFile;
    private final Path configFile;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler;
    private volatile long configMtimeMillis = -1L;

    /**
     * @param onMaintenanceDue after feeds/templates; typically enqueue cache GC + schedule host
     *     warmup (AOT/cal). Must not throw.
     */
    public EngineMaintenance(Consumer<String> log, StoreFeedRefresh feeds, Runnable onMaintenanceDue) {
        this.log = log != null ? log : s -> {};
        this.feeds = Objects.requireNonNull(feeds, "feeds");
        this.onMaintenanceDue = onMaintenanceDue != null ? onMaintenanceDue : () -> {};
        this.stampFile = JkDirs.state().resolve("engine-maintenance.stamp");
        this.configFile = JkDirs.userConfigFile();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-engine-maintenance");
            t.setDaemon(true);
            return t;
        });
    }

    /** Immediate first tick, then every {@link #TICK}. */
    public void start() {
        long sec = Math.max(1, TICK.toSeconds());
        scheduler.scheduleWithFixedDelay(this::tickQuietly, 0, sec, TimeUnit.SECONDS);
    }

    void tickQuietly() {
        if (closed.get()) return;
        try {
            maybeReloadConfig();
        } catch (Throwable ignored) {
        }
        try {
            if (maintenanceDue()) {
                runMaintenanceCycle();
            }
        } catch (Throwable ignored) {
        }
    }

    private void maybeReloadConfig() throws IOException {
        if (!Files.isRegularFile(configFile)) {
            configMtimeMillis = -1L;
            return;
        }
        long mtime = Files.getLastModifiedTime(configFile).toMillis();
        if (configMtimeMillis < 0) {
            configMtimeMillis = mtime;
            return;
        }
        if (mtime != configMtimeMillis) {
            configMtimeMillis = mtime;
            // Callers re-resolve JkCacheConfig / HostWarmup.enabled / templates each use.
            // Invalidate calibration memo so a changed probe policy can re-read host-metrics.
            try {
                cc.jumpkick.runtime.Calibration.invalidateMemo();
            } catch (Throwable ignored) {
            }
            log.accept("jk engine: reloaded user config (mtime changed)");
        }
    }

    private boolean maintenanceDue() {
        try {
            if (!Files.isRegularFile(stampFile)) return true;
            long last = Long.parseLong(Files.readString(stampFile, StandardCharsets.UTF_8).trim());
            return System.currentTimeMillis() - last >= MAINTENANCE_INTERVAL.toMillis();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Full cycle: store feeds + official templates, then GC/warmup enqueue, then stamp. Failures
     * inside feeds are already quiet.
     */
    void runMaintenanceCycle() {
        if (closed.get()) return;
        feeds.refreshFeedsQuietly();
        cc.jumpkick.templates.OfficialTemplatesFreshen.refreshQuiet(log);
        try {
            onMaintenanceDue.run();
        } catch (Throwable ignored) {
        }
        try {
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
    }
}
