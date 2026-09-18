// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.VersionSelector;
import org.jspecify.annotations.Nullable;

/**
 * The {@code kotlin} an import writes for the Kotlin a build declares: the declared version, or
 * jk's floor — the compiler jk drives for anything below it — with a Tier-2 row naming what the
 * build declared and what to check. Written at the floor, the manifest says what jk does.
 */
public final class ImportedKotlin {

    private ImportedKotlin() {}

    /** {@code declared} itself, or the floor with a row in {@code report} when it is an exact version below the floor. */
    public static @Nullable VersionSelector floored(@Nullable VersionSelector declared, ImportReport.Builder report) {
        if (!(declared instanceof VersionSelector.Exact exact) || !KotlinResolver.belowFloor(exact.version())) {
            return declared;
        }
        report.warning("Kotlin `" + exact.version() + "` is below jk's floor `" + KotlinResolver.FLOOR_VERSION
                + "`, the oldest Kotlin its compile path drives; `kotlin = \"" + KotlinResolver.FLOOR_VERSION
                + "\"` is written and the module compiles with it — check the build against that compiler's"
                + " warnings and language changes.");
        return VersionSelector.parse(KotlinResolver.FLOOR_VERSION);
    }
}
