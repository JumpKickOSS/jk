// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where each platform pin came from, by {@code group:artifact}: a BOM's {@code group:artifact:version}
 * or a {@code [managed-dependencies]} entry's {@code jk.toml:<handle>}. A conflict names it beside a
 * dependency whose versions the pin decided.
 */
record PinOrigins(Map<String, String> byModule) {

    static final PinOrigins NONE = new PinOrigins(Map.of());

    PinOrigins {
        byModule = Map.copyOf(byModule);
    }

    /**
     * The origin of {@code ga}'s {@code pin} when {@code constraint} is what the pin makes of an
     * edge: exactly the pin, or, under {@link PlatformPolicy#FLOOR}, the pin and above. An edge whose
     * own version lifted it past the pin, or that met no pin, is its own say: {@code null}.
     */
    @Nullable
    String of(String ga, @Nullable String pin, PlatformPolicy policy, VersionSet constraint) {
        String origin = byModule.get(ga);
        if (origin == null || pin == null) return null;
        VersionSet pinned = policy == PlatformPolicy.FLOOR ? VersionSet.atLeast(pin, true) : VersionSet.exact(pin);
        return sameVersions(constraint, pinned) ? origin : null;
    }

    /** True when {@code a} and {@code b} allow the same versions, however each is spelled. */
    static boolean sameVersions(VersionSet a, VersionSet b) {
        return a.equals(b)
                || (a.intersect(b.complement()).isEmpty()
                        && b.intersect(a.complement()).isEmpty());
    }
}
