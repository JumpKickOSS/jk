// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.host.ProcessEnv;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Ties an engine's life to another process: when {@value #PROPERTY} names a pid, the engine
 * stops itself once that process is gone.
 *
 * <p>The engine stays resident until told to stop — that is the product. A sandbox engine has
 * no one to tell it: a test JVM that spawns one (every CLI integration suite does) and then dies
 * mid-run — a killed worker, an OOM, Ctrl-C — leaves a JVM parked under {@code test-jk-home}
 * until someone finds it with {@code ps}. The spawner cannot be the signal (an engine detaches
 * into its own session by design, so its parent is always init), so the owner names itself and
 * the engine polls. The pid's start time is checked too: a recycled pid must not keep a dead
 * owner's engine alive. A startup-cache trainer engine is owned by the engine that records it.
 */
public final class OwnerWatchdog {

    /** System property naming the owning process's pid; forwarded by the spawner as {@code -D}. */
    public static final String PROPERTY = "jk.engine.owner-pid";

    /**
     * The same, from the environment: an owner that drives the native client, which takes no
     * {@code -D}, names itself here. {@link #PROPERTY} wins when both are set.
     */
    public static final String ENV = "JK_ENGINE_OWNER_PID";

    static final Duration POLL = Duration.ofSeconds(2);

    private OwnerWatchdog() {}

    /** The owner pid text from {@link #PROPERTY}, else {@link #ENV}; null when neither names one. */
    static @Nullable String ownerPid(
            Function<String, @Nullable String> properties, Function<String, @Nullable String> env) {
        String property = properties.apply(PROPERTY);
        return property != null && !property.isBlank() ? property : env.apply(ENV);
    }

    /** Start the watch this process was given, by {@link #PROPERTY} or {@link #ENV}. */
    public static @Nullable Thread start(Runnable onGone, Consumer<String> log) {
        return start(ownerPid(System::getProperty, ProcessEnv::get), onGone, log);
    }

    /**
     * Start the watch described by {@code pidText} (the {@value #PROPERTY} value), or return
     * {@code null} when there is nothing to watch. An owner already gone stops the engine at once.
     */
    public static @Nullable Thread start(@Nullable String pidText, Runnable onGone, Consumer<String> log) {
        if (pidText == null || pidText.isBlank()) return null;
        long pid;
        try {
            pid = Long.parseLong(pidText.trim());
        } catch (NumberFormatException e) {
            log.accept("jk engine: ignoring " + PROPERTY + "=" + pidText + " (not a pid)");
            return null;
        }
        Optional<ProcessHandle> owner = ProcessHandle.of(pid);
        if (owner.isEmpty()) {
            log.accept("jk engine: owner pid " + pid + " is already gone; stopping");
            onGone.run();
            return null;
        }
        ProcessHandle handle = owner.get();
        Optional<Instant> started = handle.info().startInstant();
        BooleanSupplier alive =
                () -> handle.isAlive() && started.equals(handle.info().startInstant());
        log.accept("jk engine: stops when owner pid " + pid + " exits");
        return watch(alive, POLL, () -> {
            log.accept("jk engine: owner pid " + pid + " is gone; stopping");
            onGone.run();
        });
    }

    /** The poll loop itself, with liveness abstracted so a test can flip it. */
    static Thread watch(BooleanSupplier ownerAlive, Duration poll, Runnable onGone) {
        // Engine-lifetime poll of the owner pid; reads no session.
        Thread t = new Thread(
                () -> {
                    while (ownerAlive.getAsBoolean()) {
                        try {
                            Thread.sleep(poll.toMillis());
                        } catch (InterruptedException e) {
                            return; // the engine is stopping on its own
                        }
                    }
                    onGone.run();
                },
                "jk-engine-owner-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
