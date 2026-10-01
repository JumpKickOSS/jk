// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The per-cache-root stores of a persisted memo ({@link FileHashMemo}, {@link AbiMemo}), at most
 * {@link #MAX_LOADED} in memory at once. An engine normally serves one cache root, but each
 * {@code JK_CACHE_DIR} is another, and every store is bounded on its own, so the number of roots
 * is what has to be bounded here. Loading one more persists and drops the least recently used.
 *
 * @param <S> the store type
 */
final class CacheRootStores<S> {

    /** Stores kept in memory; two lets a build and a concurrent one in another cache root both stay warm. */
    static final int MAX_LOADED = 2;

    private record Slot<S>(S store, AtomicLong used) {}

    private final ConcurrentMap<Path, Slot<S>> slots = new ConcurrentHashMap<>();
    private final AtomicLong tick = new AtomicLong();
    private final Function<Path, S> load;
    private final Consumer<S> persist;

    /**
     * @param load reads the store of a normalized cache root from disk
     * @param persist writes a store back; runs on the store an eviction drops
     */
    CacheRootStores(Function<Path, S> load, Consumer<S> persist) {
        this.load = load;
        this.persist = persist;
    }

    /** The store of {@code cacheRoot}, loading it, and evicting the coldest other one, when absent. */
    S of(Path cacheRoot) {
        Path root = cacheRoot.toAbsolutePath().normalize();
        Slot<S> slot = slots.get(root);
        if (slot == null) {
            slot = slots.computeIfAbsent(root, r -> new Slot<>(load.apply(r), new AtomicLong()));
            slot.used().set(tick.incrementAndGet());
            evictBeyondCap();
        } else {
            slot.used().set(tick.incrementAndGet());
        }
        return slot.store();
    }

    /** Every loaded store. */
    Iterable<S> loaded() {
        return slots.values().stream().map(Slot::store).toList();
    }

    /** How many stores are in memory. */
    int size() {
        return slots.size();
    }

    /** Forget every store without persisting it. */
    void clear() {
        slots.clear();
    }

    private synchronized void evictBeyondCap() {
        while (slots.size() > MAX_LOADED) {
            Map.Entry<Path, Slot<S>> coldest = null;
            for (Map.Entry<Path, Slot<S>> e : slots.entrySet()) {
                if (coldest == null
                        || e.getValue().used().get() < coldest.getValue().used().get()) coldest = e;
            }
            if (coldest == null) return;
            if (slots.remove(coldest.getKey(), coldest.getValue()))
                persist.accept(coldest.getValue().store());
        }
    }
}
