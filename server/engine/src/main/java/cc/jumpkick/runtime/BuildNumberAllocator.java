// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.plugin.protocol.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable monotonic per-project build numbers allocated at <em>request-start</em> (JK-1250).
 *
 * <p>Finish-time {@link BuildMetrics#record} still trains stats; it must <strong>not</strong> mint
 * a second number. Numbers live in {@code ~/.jk/state/builds/run-numbers.json} keyed by canonical
 * project dir, advanced under a process lock and never decrease.
 */
public final class BuildNumberAllocator {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private BuildNumberAllocator() {}

    public static Path defaultFile() {
        return JkDirs.builds().resolve("run-numbers.json");
    }

    /**
     * Next build number for {@code projectDir} (≥ 1). {@code metricsFile} is consulted so a fresh
     * allocator file still continues past historical {@link BuildMetrics} run counts.
     */
    public static long allocate(Path countersFile, Path metricsFile, String projectDir) {
        if (projectDir == null || projectDir.isBlank()) return 0;
        String key = BuildMetrics.baseDir(projectDir);
        LOCK.lock();
        try {
            Map<String, Long> map = read(countersFile);
            long stored = map.getOrDefault(key, 0L);
            long fromMetrics = 0;
            if (metricsFile != null) {
                try {
                    BuildMetrics m = BuildMetrics.load(metricsFile);
                    fromMetrics = m.projectRunCount(key);
                } catch (RuntimeException ignored) {
                    fromMetrics = 0;
                }
            }
            long next = Math.max(stored, fromMetrics) + 1;
            map.put(key, next);
            write(countersFile, map);
            return next;
        } finally {
            LOCK.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> read(Path file) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) return out;
        try {
            Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m)) return out;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) continue;
                if (e.getValue() instanceof Number n) out.put(k, n.longValue());
            }
        } catch (IOException | RuntimeException ignored) {
            // empty
        }
        return out;
    }

    private static void write(Path file, Map<String, Long> map) {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> flat = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : map.entrySet()) {
                flat.put(e.getKey(), e.getValue());
            }
            AtomicWrites.replace(file, MiniJson.write(flat));
        } catch (IOException | RuntimeException ignored) {
            // advisory
        }
    }
}
