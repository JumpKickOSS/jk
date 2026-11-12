// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One reentrant lock per key, kept only while someone holds or waits for it: the entry is created
 * by the first taker and removed when the last one lets go. A {@code computeIfAbsent(key, new
 * Object())} map does the same job and keeps one entry for every key the process ever locked, which
 * in a resident engine is every path it has served for days.
 *
 * <pre>{@code
 * try (KeyedLocks.Held held = LOCKS.hold(root)) {
 *     ...
 * }
 * }</pre>
 *
 * @param <K> the lock key, compared by {@link Object#equals}
 */
public final class KeyedLocks<K> {

    /** A lock and how many threads hold or wait for it; guarded by the map's per-key compute. */
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final ConcurrentHashMap<K, Entry> entries = new ConcurrentHashMap<>();

    /** A held key; closing it releases the lock once. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** Take {@code key}'s lock, waiting as long as it takes. */
    public Held hold(K key) {
        Entry e = join(key);
        e.lock.lock();
        return () -> release(key, e);
    }

    /** As {@link #hold}, giving up the wait when the thread is interrupted. */
    public Held holdInterruptibly(K key) throws InterruptedException {
        Entry e = join(key);
        try {
            e.lock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            leave(key);
            throw interrupted;
        }
        return () -> release(key, e);
    }

    /** Whether this thread holds {@code key}'s lock: a reentrant caller already inside it. */
    public boolean heldByCurrentThread(K key) {
        Entry e = entries.get(key);
        return e != null && e.lock.isHeldByCurrentThread();
    }

    /** How many keys are held or waited for right now. */
    public int size() {
        return entries.size();
    }

    private Entry join(K key) {
        return entries.compute(key, (k, e) -> {
            Entry out = e == null ? new Entry() : e;
            out.users++;
            return out;
        });
    }

    private void release(K key, Entry e) {
        e.lock.unlock();
        leave(key);
    }

    private void leave(K key) {
        entries.computeIfPresent(key, (k, e) -> --e.users == 0 ? null : e);
    }
}
