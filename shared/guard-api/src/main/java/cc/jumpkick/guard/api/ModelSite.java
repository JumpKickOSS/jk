// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import org.jspecify.annotations.Nullable;

/**
 * A fact of the build model — a manifest key, a lock entry, the tier table — as a site. The
 * fingerprint is the model key; the file is the manifest or lock that declares it, when one does.
 */
public non-sealed interface ModelSite extends Site {

    /** The model key, e.g. {@code repository:corp}, {@code java root}, {@code tiers}. */
    String key();

    @Override
    default String fingerprint() {
        return key();
    }

    @Override
    default @Nullable String file() {
        return null;
    }

    @Override
    default int line() {
        return 0;
    }
}
