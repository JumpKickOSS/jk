// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.jdk.HostPlatform;
import java.util.Locale;

/**
 * The platform a cache record was made on, {@code <os>-<arch>} ({@code linux-x86_64}, {@code
 * macos-aarch64}, {@code windows-x86_64}): a field of the record and a dimension of a lookup, never
 * an ingredient of an action key, which names the platform only where the outputs depend on it.
 */
public final class CachePlatform {

    private static final String CURRENT = of(HostPlatform.currentOs(), HostPlatform.currentArch());

    private CachePlatform() {}

    /** This host's platform. */
    public static String current() {
        return CURRENT;
    }

    static String of(String os, String arch) {
        return os.toLowerCase(Locale.ROOT) + "-" + arch.toLowerCase(Locale.ROOT);
    }
}
