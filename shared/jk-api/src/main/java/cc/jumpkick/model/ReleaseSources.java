// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;

/**
 * One {@code [multi-release]} entry: the module-relative source roots compiled at {@code --release
 * release} against the main classes, whose classes the jar carries under {@code
 * META-INF/versions/<release>/}.
 */
public record ReleaseSources(int release, List<String> src) {

    /** The lowest release a JDK reads from {@code META-INF/versions/}. */
    public static final int FLOOR = 9;

    public ReleaseSources {
        if (release < FLOOR) {
            throw new IllegalArgumentException(
                    "a multi-release entry is for Java " + FLOOR + " or newer, got " + release);
        }
        src = src == null ? List.of() : List.copyOf(src);
    }
}
