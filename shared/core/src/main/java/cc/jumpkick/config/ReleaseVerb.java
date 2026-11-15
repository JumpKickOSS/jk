// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The verb a request runs under, as far as the release rule cares ({@code ReleaseSession}): inside
 * git any verb builds the release only from a clean checkout at the version's tag, which {@code jk
 * release} creates and {@code jk publish} requires; outside git, release and publish are releases by
 * the verb itself and every other verb builds a snapshot.
 */
public enum ReleaseVerb {
    /** Every verb but release and publish. */
    NONE,
    /** {@code jk release}: the release it is cutting. */
    RELEASE,
    /** {@code jk publish}: uploads only a release. */
    PUBLISH;

    /** The wire spelling: the constant in lower case. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The inverse of {@link #wireName}; anything else is {@link #NONE}. */
    public static ReleaseVerb parse(@Nullable String wire) {
        for (ReleaseVerb v : values()) {
            if (v.wireName().equals(wire)) return v;
        }
        return NONE;
    }
}
