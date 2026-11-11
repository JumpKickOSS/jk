// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.run.StepScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every class file's stamp under a classes directory, walked once per request for as long as no
 * step has ended: the forecast, a module lane and the workspace lane all ask about the same trees,
 * and only a step writes one. A walk taken before a step ended is walked again, so a compile that
 * lands between two asks is always seen. Off a request every ask walks.
 */
final class ClassStamps {

    /** Heap the remembered stamps of one request may take; past it a tree is walked on every ask. */
    static final long MAX_BYTES = 8L << 20;

    private record Walked(long steps, Map<String, String> stamps, long bytes) {}

    /** One request's walks; charged to {@link #MAX_BYTES}, not to the scope's own budget. */
    static final class Memo {
        private final ConcurrentHashMap<Path, Walked> trees = new ConcurrentHashMap<>();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicInteger walks = new AtomicInteger();

        /** How many trees this request has walked. */
        int walks() {
            return walks.get();
        }
    }

    private record Key() {}

    private static final Key KEY = new Key();

    private ClassStamps() {}

    /** The ambient request's memo; a fresh one on every call off a request. */
    static Memo memo() {
        return RequestScope.current().get(KEY, k -> new Memo());
    }

    /** {@code relPath →} {@link StatIdentity} for every class file under {@code classesDir}, sorted. */
    static Map<String, String> of(Path classesDir) throws IOException {
        Path root = classesDir.toAbsolutePath().normalize();
        Memo memo = memo();
        long steps = StepScope.closed();
        Walked had = memo.trees.get(root);
        if (had != null && had.steps() == steps) return had.stamps();
        memo.walks.incrementAndGet();
        SortedMap<String, String> stamps = Collections.unmodifiableSortedMap(walk(root));
        long bytes = 64L * stamps.size();
        for (Map.Entry<String, String> e : stamps.entrySet())
            bytes += 2L * (e.getKey().length() + e.getValue().length());
        if (memo.bytes.addAndGet(bytes) > MAX_BYTES) {
            memo.bytes.addAndGet(-bytes);
            return stamps;
        }
        Walked previous = memo.trees.put(root, new Walked(steps, stamps, bytes));
        if (previous != null) memo.bytes.addAndGet(-previous.bytes());
        return stamps;
    }

    private static TreeMap<String, String> walk(Path root) throws IOException {
        TreeMap<String, String> out = new TreeMap<>();
        PathUtil.forEachRegularFile(root, (p, attrs) -> {
            String rel =
                    root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (!rel.endsWith(".class")) return;
            out.put(rel, StatIdentity.of(p, attrs).encode());
        });
        return out;
    }
}
