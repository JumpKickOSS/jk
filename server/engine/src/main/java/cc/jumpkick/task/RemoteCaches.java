// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.ContextPropagator;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The remote tier the action cache consults after the local one, bound for the extent of a build
 * and carried to every thread its work runs on. Unbound — every build today — the action cache is
 * the local disk alone.
 */
public final class RemoteCaches {

    private static final ScopedValue<RemoteTier> CURRENT = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(CURRENT);
    }

    private RemoteCaches() {}

    /** The tier bound here, or null when the build has none. */
    public static @Nullable RemoteTier current() {
        return ContextPropagator.get(CURRENT);
    }

    /** Run {@code body} with {@code tier} as the remote tier of every action cache it opens. */
    public static <T> T where(RemoteTier tier, Callable<T> body) throws Exception {
        return ScopedValue.where(CURRENT, tier).<T, Exception>call(body::call);
    }
}
