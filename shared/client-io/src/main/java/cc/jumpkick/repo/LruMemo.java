// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * A thread-safe memo bounded by the total weight of its values: past {@code maxWeight} the
 * least-recently-used entries go first, so a long session keeps what it keeps asking for instead
 * of starting over.
 */
public final class LruMemo<K, V> {

    private final long maxWeight;
    private final ToLongFunction<V> weigher;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(256, 0.75f, true);
    private long weight;

    /** {@code weigher} prices one value; a value is never priced below one. */
    public LruMemo(long maxWeight, ToLongFunction<V> weigher) {
        if (maxWeight < 1) throw new IllegalArgumentException("maxWeight must be positive: " + maxWeight);
        this.maxWeight = maxWeight;
        this.weigher = weigher;
    }

    public synchronized @Nullable V get(K key) {
        return entries.get(key);
    }

    /** Store {@code value} under {@code key}, then evict least-recently-used entries past the bound. */
    public synchronized void put(K key, V value) {
        V old = entries.put(key, value);
        if (old != null) weight -= priced(old);
        weight += priced(value);
        Iterator<Map.Entry<K, V>> eldest = entries.entrySet().iterator();
        while (weight > maxWeight && eldest.hasNext()) {
            Map.Entry<K, V> e = eldest.next();
            if (e.getKey().equals(key) && entries.size() == 1) break;
            weight -= priced(e.getValue());
            eldest.remove();
        }
    }

    public synchronized void remove(K key) {
        V old = entries.remove(key);
        if (old != null) weight -= priced(old);
    }

    /** Drop every entry and return how many went. */
    public synchronized int clear() {
        int n = entries.size();
        entries.clear();
        weight = 0;
        return n;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long weight() {
        return weight;
    }

    private long priced(V value) {
        return Math.max(1, weigher.applyAsLong(value));
    }
}
