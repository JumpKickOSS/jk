// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.Objects;

/**
 * User JDK spec from {@code jk jdk install} or {@code .jdk-version} (bare major, exact version,
 * or vendor-qualified), matched against the JetBrains feed aliases.
 */
public record JdkSpec(String value) {

    public JdkSpec {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("spec is empty");
        }
    }

    public static JdkSpec parse(String raw) {
        return new JdkSpec(raw);
    }

    /** {@code true} when the spec is just a version number ({@code 21}, {@code 21.0.5}). */
    public boolean bareVersion() {
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        return first >= '0' && first <= '9';
    }

    /** Lower-case comparison form used when matching against feed aliases. */
    public String normalized() {
        return value.toLowerCase();
    }
}
