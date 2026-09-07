// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.archunit;

import com.tngtech.archunit.library.freeze.ViolationLineMatcher;

/**
 * {@code FreezingArchRule}'s line matcher with jk's normalisation: two violation lines are the same
 * violation when they agree after source line numbers and synthetic ordinals are folded away, which
 * is exactly how the baseline's {@code at} entries are spelled.
 */
public final class JkLineMatcher implements ViolationLineMatcher {

    @Override
    public boolean matches(String lineFromFirstViolation, String lineFromSecondViolation) {
        return JkArchUnit.normalise(lineFromFirstViolation).equals(JkArchUnit.normalise(lineFromSecondViolation));
    }
}
