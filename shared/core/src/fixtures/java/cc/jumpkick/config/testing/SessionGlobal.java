// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link SessionContext}'s process-wide static — the original leak bounded.
 *
 * <p>Reads {@link SessionContext#installed()}, not {@link SessionContext#current()}: the latter
 * prefers the calling thread's {@code ScopedValue} binding, so snapshotting it and writing it back
 * would publish one thread's session to every other.
 */
public final class SessionGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "session";
    }

    /**
     * Bounded but not attributed. Every CLI command test writes this: the entry point installs the
     * resolved session, so exercising any command necessarily moves it. Measured on the integration
     * tier — 48 classes, 324 methods. Reporting each would bury the globals whose writes are rare
     * enough to mean something.
     */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(SessionContext.installed());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (captured instanceof Session s) SessionContext.install(s);
    }
}
