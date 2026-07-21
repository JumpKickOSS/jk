// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;

/**
 * The {@code deny { ... }} policy: repository host denylist, SPDX license deny/allow lists, and
 * yanked-package policy ({@code deny}/{@code warn}/{@code allow}).
 */
public record DenyPolicy(
        List<String> deniedSources, List<String> deniedLicenses, List<String> allowedLicenses, YankedPolicy yanked) {

    public enum YankedPolicy {
        DENY,
        WARN,
        ALLOW
    }

    public DenyPolicy {
        deniedSources = deniedSources == null ? List.of() : List.copyOf(deniedSources);
        deniedLicenses = deniedLicenses == null ? List.of() : List.copyOf(deniedLicenses);
        allowedLicenses = allowedLicenses == null ? List.of() : List.copyOf(allowedLicenses);
        yanked = Objects.requireNonNullElse(yanked, YankedPolicy.DENY);
    }

    public static DenyPolicy permissive() {
        return new DenyPolicy(List.of(), List.of(), List.of(), YankedPolicy.WARN);
    }

    public boolean isEmpty() {
        return deniedSources.isEmpty() && deniedLicenses.isEmpty() && allowedLicenses.isEmpty();
    }
}
