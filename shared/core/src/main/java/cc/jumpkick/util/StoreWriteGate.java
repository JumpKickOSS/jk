// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Process-wide gate between store writers and the store wipe.
 *
 * <p>The engine mutates the store from several background paths — template clones, feed
 * refreshes, artifact downloads, CAS puts — while {@code jk storage nuke} deletes the store root
 * outright. On Windows a concurrent writer's open handle turns that delete into a sharing
 * violation (the wiped directory cannot be removed while a {@code .put-*.tmp} inside it is still
 * open), and a writer that starts after the wipe recreates the store the nuke just promised was
 * gone. Writers hold the shared side — many at once, uncontended in normal operation; the wipe
 * holds the exclusive side, waiting out in-flight writes and blocking new ones until the delete
 * finishes.
 */
public final class StoreWriteGate {

    private static final ReentrantReadWriteLock GATE = new ReentrantReadWriteLock();

    private static volatile boolean wiped;

    private StoreWriteGate() {}

    /**
     * Whether a store wipe has happened in this process. Background hygiene writers (warmup
     * passes, feed refreshes, template freshens) stand down once true — a queued hygiene write
     * landing after the wipe would recreate the store the nuke just reported gone. User-driven
     * paths (builds, {@code jk new}) ignore this: repopulating on demand is their job.
     */
    public static boolean wipedSinceStart() {
        return wiped;
    }

    /**
     * Forget a wipe: the flag is engine-lifetime state, and a test JVM that wiped a store in one
     * class would otherwise keep every later class's background writer switched off.
     */
    public static void resetForTests() {
        wiped = false;
    }

    /** A held side of the gate; release with try-with-resources. */
    @FunctionalInterface
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** Hold for the duration of one store write (shared — writers never block each other). */
    public static Held write() {
        GATE.readLock().lock();
        return GATE.readLock()::unlock;
    }

    /** Hold for the store wipe (exclusive — waits out writers, blocks new ones). */
    public static Held wipe() {
        GATE.writeLock().lock();
        wiped = true;
        return GATE.writeLock()::unlock;
    }
}
