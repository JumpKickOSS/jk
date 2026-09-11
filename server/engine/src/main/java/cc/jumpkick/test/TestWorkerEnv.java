// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
     */
    static Map<String, String> forWorker(Map<String, String> base, int workerId, Path tmp) {
        Map<String, String> env = new LinkedHashMap<>(base);
        env.put("TMPDIR", tmp.toString());
        env.put("TMP", tmp.toString());
        env.put("TEMP", tmp.toString());
        // A CHILD of the run's state dir, not a sibling of it. The sibling spelling
        // (`<base>-w0`) put every worker's state outside the one directory the run cleans, so
        // deleting `<base>` recursively never reached them and they accumulated under /tmp
        // forever. TestTmpDir.forWorker already splits the temp root this way; one idea deserves
        // one spelling, and this is the one that cannot leak.
        env.computeIfPresent(
                "JK_STATE_DIR", (k, dir) -> Path.of(dir).resolve("w" + workerId).toString());
        return env;
    }

    /**
     * Inactivity window for pull-mode test workers. Generous: single tests are legitimately
     * slow (the Android ladder runs minutes per class), but the runner emits an event per test
     * start/finish, so a silent worker is a hung one — a JLine tty probe once stalled a worker
     * (and the whole suite) for 3.5h with zero output. Override:
     * {@code -Djk.test.worker.idle.ms} / {@code JK_TEST_WORKER_IDLE_MS}; {@code 0} disables.
     */
    static long idleTimeoutMs() {
        String prop = System.getProperty("jk.test.worker.idle.ms", System.getenv("JK_TEST_WORKER_IDLE_MS"));
        if (prop != null && !prop.isBlank()) {
            try {
                return Long.parseLong(prop.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 10 * 60_000L;
    }
}
