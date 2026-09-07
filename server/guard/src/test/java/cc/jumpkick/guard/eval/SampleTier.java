// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

/** A fixture enum the extractor tests read from its own class file. */
enum SampleTier {
    UNIT,
    INTEGRATION,
    SLOW;

    static final String KIND = "tier";
    static final int FLOOR = 3;
}
