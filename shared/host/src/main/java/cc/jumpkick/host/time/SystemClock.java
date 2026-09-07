// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host.time;

/** The JVM's clocks; the only class allowed to read them. */
final class SystemClock implements Clock {
    @Override
    public long millis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanos() {
        return System.nanoTime();
    }
}
