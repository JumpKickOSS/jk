// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import org.jspecify.annotations.Nullable;

/** Raised when a {@code jk.toml} file cannot be parsed or fails minimal validation. */
public final class JkBuildParseException extends RuntimeException {

    public JkBuildParseException(@Nullable String message) {
        super(message);
    }

    public JkBuildParseException(@Nullable String message, Throwable cause) {
        super(message, cause);
    }
}
