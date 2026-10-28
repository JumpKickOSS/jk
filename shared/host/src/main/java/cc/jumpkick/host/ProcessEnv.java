// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * This process's own environment, and the one place jk reads it.
 *
 * <p>In the CLI that is the caller's shell. In the engine it is whichever shell started the daemon,
 * possibly days ago, so engine code reads it only for jk's own settings ({@code JK_COMPILE_LANES},
 * the worker budget) and for whether the machine itself is a CI runner. Anything the jk command
 * being served decides (its {@code CI}, credentials, {@code PATH}, a toolchain) reads {@code
 * BuildEnv.get} instead, which answers from the request first.
 */
public final class ProcessEnv {

    private ProcessEnv() {}

    /** The variable's value in this process, or {@code null} when unset. */
    public static @Nullable String get(String name) {
        return System.getenv(name);
    }

    /** Every variable of this process, unmodifiable. */
    public static Map<String, String> all() {
        return System.getenv();
    }
}
