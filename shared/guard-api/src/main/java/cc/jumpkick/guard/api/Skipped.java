// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/**
 * Thrown by a guard that cannot run on this machine — the tool it shells out to is not installed
 * and no fallback is at hand. The engine reports the guard {@code skipped} with the message as a
 * notice: not red, and not a verdict either, so the lane is evaluated again on the next build rather
 * than remembered as clean. A guard that does not apply on this operating system at all throws
 * {@link #onThisPlatform}: the same notice, and a verdict for this platform, so the lane is not
 * evaluated again for a reason no install can change. A guard that must run under CI checks the
 * {@code CI} variable itself and reports a violation there instead of skipping.
 */
public final class Skipped extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean platform;

    public Skipped(String message) {
        this(message, false);
    }

    private Skipped(String message, boolean platform) {
        super(message);
        this.platform = platform;
    }

    /** The guard does not apply on this operating system; nothing installed here would change that. */
    public static Skipped onThisPlatform(String message) {
        return new Skipped(message, true);
    }

    /** Whether this skip stands for every run on this operating system. */
    public boolean platform() {
        return platform;
    }
}
