// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

/**
 * One tolerated violation. A site entry names a fingerprint; a metric entry names a unit and the
 * value it may reach. Every entry carries the reason a human gave when it was frozen, and the lane
 * it was observed in: a module-lane rule is reconciled one module at a time, so an entry another
 * module owns is neither matched nor stale here.
 */
public sealed interface Entry {

    String reason();

    /** The key an observation matches on: the fingerprint, or the metric unit. */
    String key();

    /** The module lane the entry belongs to, or {@code ""} for a rule the tree/model/workspace lane owns. */
    String in();

    /** The same entry attributed to {@code lane}. */
    Entry in(String lane);

    record Site(String at, String reason, String in) implements Entry {
        public Site(String at, String reason) {
            this(at, reason, "");
        }

        @Override
        public String key() {
            return at;
        }

        @Override
        public Entry in(String lane) {
            return new Site(at, reason, lane);
        }
    }

    record Metric(String unit, double value, String reason, String in) implements Entry {
        public Metric(String unit, double value, String reason) {
            this(unit, value, reason, "");
        }

        @Override
        public String key() {
            return unit;
        }

        @Override
        public Entry in(String lane) {
            return new Metric(unit, value, reason, lane);
        }
    }
}
