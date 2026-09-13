// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/**
 * Thrown by a guard that cannot run on this machine — the tool it shells out to is not installed
 * and no fallback is at hand. The engine reports the guard {@code skipped} with the message as a
 * notice: not red, and not a verdict either, so the lane is evaluated again on the next build rather
 * than remembered as clean. A guard that must run under CI checks the {@code CI} variable itself
 * and reports a violation there instead of skipping.
 */
public final class Skipped extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Skipped(String message) {
        super(message);
    }
}
