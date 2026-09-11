// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import org.jspecify.annotations.Nullable;

/**
 * IDE model or generation failed for a reason the user can act on, carrying the exit code the CLI
 * returns. A {@code null} message means the failure was already reported.
 */
public final class IdeException extends RuntimeException {

    private final int code;

    public IdeException(int code, @Nullable String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
