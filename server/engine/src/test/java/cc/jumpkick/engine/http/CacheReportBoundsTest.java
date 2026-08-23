// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.Bound;
import cc.jumpkick.task.CacheTier;
import org.junit.jupiter.api.Test;

/**
 * What the report is allowed to promise. A {@code *MaxBytes} field is a denominator, and a
 * denominator says the tier is held to that many bytes — so only a tier actually bounded by bytes
 * may publish one. The tiers capped by count or cleared by reset are held to something else
 * entirely, and on those a percentage would be measured against a number that is not their bound.
 *
 * <p>{@code formatStampsMax} is the shape that is allowed: a count against a count cap.
 */
class CacheReportBoundsTest {

    @Test
    void only_a_tier_bounded_by_bytes_publishes_a_byte_denominator() {
        String json = new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 << 20, 1 << 30, 0, 0, 0, 0, 0)
                .toJson()
                .toString();

        for (CacheTier tier : CacheTier.values()) {
            if (boundedByBytes(tier)) continue;
            assertThat(json)
                    .as("%s is not bounded by bytes and must not report as though it were", tier)
                    .doesNotContain("\"" + camel(tier.entry()) + "MaxBytes\"");
        }
        assertThat(json)
                .as("the two real byte budgets still publish theirs")
                .contains("\"actionMaxBytes\"", "\"incrementalMaxBytes\"");
    }

    /**
     * Only the action budget. A reset budget is not a denominator either: at 99 % of it nothing is
     * deleted and at 101 % the tier is empty, so a utilization bar against it would describe a
     * gradual pressure that does not exist.
     */
    private static boolean boundedByBytes(CacheTier tier) {
        return tier.bound().kind() == Bound.Kind.DELEGATED;
    }

    /** {@code hash-memo} → {@code hashMemo}, the field name such a denominator would take. */
    private static String camel(String entry) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : entry.toCharArray()) {
            if (c == '-' || c == '.') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
