// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

/**
 * One tolerated violation. A site entry names a fingerprint; a metric entry names a unit and the
 * value it may reach. Every entry carries the reason a human gave when it was frozen.
 */
public sealed interface Entry {

    String reason();

    /** The key an observation matches on: the fingerprint, or the metric unit. */
    String key();

    record Site(String at, String reason) implements Entry {
        @Override
        public String key() {
            return at;
        }
    }

    record Metric(String unit, double value, String reason) implements Entry {
        @Override
        public String key() {
            return unit;
        }
    }
}
