// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.HostLoad;
import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.util.KeychainSandbox;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What a pull-mode test worker runs under: its private env and how long it may stay silent. */
final class TestWorkerEnv {

    private TestWorkerEnv() {}

    /**
     * Per-worker env when {@code W > 1}: private temp root, and for nested-engine suites a
     * per-worker {@code JK_STATE_DIR}. Engine identity is keyed on (state, store), so a shared
     * state dir means one socket for every worker — and one worker's engine force-stop aborts
     * its siblings mid-request. The suffix stays short: the state dir holds Unix domain sockets,
     * and the JDK stops binding past 102 characters (the budget is
     * {@code UnixSocketPaths.MAX_PATH_LENGTH}, proven there by binding).
     *
     * <p>With {@code ownHome} each worker also gets a {@code JK_HOME} of its own under the run's,
     * with the git configuration and keychain that live in it. The home holds what a test of the
     * product writes and an engine stop reads: {@code config.toml}, the materialized engine under
     * {@code lib}, {@code bin}, credentials. A stop of "this home's engines" claims every engine
     * whose command line names a path inside the home, so on a shared home one worker's stop takes
     * its siblings' engines down. The store, cache, JDKs and Maven local repository are pinned by
     * their own variables and stay shared.
     */
    static WorkerEnv forWorker(WorkerEnv base, int workerId, Path tmp, boolean ownHome) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TMPDIR", tmp.toString());
        env.put("TMP", tmp.toString());
        env.put("TEMP", tmp.toString());
        // A child of the run's state dir, as TestTmpDir.forWorker splits the temp root, so deleting
        // the run's dir reaches every worker's.
        Map<String, String> extras = base.extras();
        String stateDir = extras.get("JK_STATE_DIR");
        if (stateDir != null)
            env.put("JK_STATE_DIR", Path.of(stateDir).resolve("w" + workerId).toString());
        String home = extras.get("JK_HOME");
        if (ownHome && home != null) {
            Path runHome = Path.of(home);
            Path workerHome = runHome.resolve("w" + workerId);
            env.put("JK_HOME", workerHome.toString());
            for (String name : HOME_FILES) {
                String value = extras.get(name);
                if (value == null) continue;
                Path path = Path.of(value);
                if (path.startsWith(runHome))
                    env.put(name, workerHome.resolve(runHome.relativize(path)).toString());
            }
        }
        return base.with(env);
    }

    /** The variables naming a file or directory inside the test home that a worker home re-roots. */
    private static final List<String> HOME_FILES = List.of("GIT_CONFIG_GLOBAL", KeychainSandbox.ENV);

    /** The silence a test worker is allowed on a quiet host before it is taken for hung. */
    static final long DEFAULT_IDLE_MS = 10 * 60_000L;

    /**
     * Inactivity window for pull-mode test workers. Generous: single tests are legitimately
     * slow (the Android ladder runs minutes per class), but the runner emits an event per test
     * start/finish, so a silent worker is a hung one — a JLine tty probe once stalled a worker
     * (and the whole suite) for 3.5h with zero output. The default stretches with the host's
     * load per processor ({@link HostLoad}): a test whose build crawls under a load average in
     * the hundreds is slow, not hung. Override, taken as written whatever the load:
     * {@code -Djk.test.worker.idle.ms} / {@code JK_TEST_WORKER_IDLE_MS}; {@code 0} disables.
     */
    static long idleTimeoutMs() {
        String prop = System.getProperty("jk.test.worker.idle.ms", ProcessEnv.get("JK_TEST_WORKER_IDLE_MS"));
        return idleTimeoutMs(prop, HostLoad.loadAverage(), HostProcessors.count());
    }

    /** How long a suite run whole in one JVM may print nothing before it is killed as hung. */
    static final long DEFAULT_SUITE_IDLE_MS = 30 * 60_000L;

    /** The single suite JVM's window: {@code [test] idle-timeout} when set (positive), else {@link #DEFAULT_SUITE_IDLE_MS}. */
    static long suiteIdleTimeoutMs(long setting) {
        return setting > 0 ? setting : DEFAULT_SUITE_IDLE_MS;
    }

    /** A pull-mode worker's window: {@code [test] idle-timeout} when set (positive), else {@link #idleTimeoutMs()}. */
    static long poolIdleTimeoutMs(long setting) {
        return setting > 0 ? setting : idleTimeoutMs();
    }

    /** {@link #idleTimeoutMs()} with its inputs explicit. */
    static long idleTimeoutMs(@Nullable String setting, double loadAverage, int processors) {
        if (setting != null && !setting.isBlank()) {
            try {
                return Long.parseLong(setting.trim());
            } catch (NumberFormatException ignored) {
                // an unreadable setting is no setting
            }
        }
        return DEFAULT_IDLE_MS * HostLoad.factor(loadAverage, processors);
    }
}
