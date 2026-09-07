// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/** Thrown by {@link Owner#require}: the engine reports the guard {@code owner-missing}, never clean. */
public final class OwnerMissing extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OwnerMissing(String message) {
        super(message);
    }
}
