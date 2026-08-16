// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.function.Supplier;

/**
 * Scope for parking a long-poll without starving the shared RPC budget: the server's
 * implementation releases the caller's admission permit around {@code blocking} and reacquires
 * it before returning, so a handler parked in a wait cannot 503 the rest of the surface.
 */
public interface AdmissionYield {

    /** No-op scope for callers that hold no admission permit (tests, embedded use). */
    AdmissionYield NONE = new AdmissionYield() {
        @Override
        public <T> T yielding(Supplier<T> blocking) {
            return blocking.get();
        }
    };

    <T> T yielding(Supplier<T> blocking);
}
