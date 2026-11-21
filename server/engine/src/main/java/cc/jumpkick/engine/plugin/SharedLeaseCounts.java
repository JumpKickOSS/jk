// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/**
 * What an engine met reading the host's shared worker-leases file ({@link HostLeases}) since it
 * started. {@code lockWaits}: reads that waited {@value HostLeases#CONTENDED_NANOS} ns or more for
 * another engine's lock; {@code reclaims}: engines that were gone whose leases or workers it
 * reclaimed.
 */
public record SharedLeaseCounts(long lockWaits, long reclaims) {

    /** An engine that shares the file with none. */
    public static final SharedLeaseCounts NONE = new SharedLeaseCounts(0, 0);

    /** This engine's counts; {@link #NONE} before it shares the file. */
    public static SharedLeaseCounts current() {
        return HostLeases.thisProcessCounts();
    }
}
