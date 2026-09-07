// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.List;
import java.util.Set;

/**
 * The test-tag tier table the root manifest declares: {@code [test]} is the fast tier, every
 * profile but {@code ci} a tier of its own. As a site its key is {@code tiers}.
 */
public interface Tiers extends ModelSite {

    /** Every tag a tier includes or excludes. */
    Set<String> tagVocabulary();

    /** The tiers that run a test carrying exactly {@code tags} — one, when the table partitions. */
    List<String> running(Set<String> tags);

    @Override
    default String key() {
        return "tiers";
    }
}
