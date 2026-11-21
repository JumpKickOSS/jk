// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link CacheRetention.Probe} that answers truthfully and counts what it was asked.
 *
 * <p>The subject of a retention test is the work the pass chooses to do, never what the host's
 * filesystem happened to record: a test that reads mtimes off disk passes or fails on the mount
 * options, while one that counts the questions holds everywhere.
 */
final class CountingProbe implements CacheRetention.Probe {

    private final AtomicInteger clocks = new AtomicInteger();
    private final AtomicInteger sizes = new AtomicInteger();

    /** Times the pass asked how old an entry is — the question ranking cannot be done without. */
    int clockQuestions() {
        return clocks.get();
    }

    /** Every question about an individual entry, of any kind. */
    int questions() {
        return clocks.get() + sizes.get();
    }

    @Override
    public long mtime(BasicFileAttributes entry) {
        clocks.incrementAndGet();
        return CacheRetention.Probe.REAL.mtime(entry);
    }

    @Override
    public long size(BasicFileAttributes entry) {
        sizes.incrementAndGet();
        return CacheRetention.Probe.REAL.size(entry);
    }
}
