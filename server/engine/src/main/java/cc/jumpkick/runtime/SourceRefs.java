// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Per-language source lists shared between an effort prediction and the plan that follows it.
 *
 * <p>{@code BuildPlanner} declares exactly these caches, with a comment saying they exist so
 * "parse-build execute reuses the result instead of walking the same directories again" — and
 * {@code predict} was called thirteen lines above their declaration, so it could not capture them
 * and walked every source tree a second time. Moving the declarations up and threading them here
 * is the whole fix.
 */
@NullMarked
public record SourceRefs(
        AtomicReference<@Nullable List<Path>> java,
        AtomicReference<@Nullable List<Path>> kotlin,
        AtomicReference<@Nullable List<Path>> groovy) {

    /** Refs nobody else shares — for a caller that predicts without a plan behind it. */
    public static SourceRefs unshared() {
        return new SourceRefs(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    }

    /** {@code ref}'s value, computing and storing it on first use. */
    static List<Path> get(AtomicReference<@Nullable List<Path>> ref, IoSupplier<List<Path>> compute)
            throws IOException {
        List<Path> hit = ref.get();
        if (hit != null) return hit;
        List<Path> fresh = compute.get();
        ref.compareAndSet(null, fresh);
        return ref.get();
    }

    /** A supplier that may fail on the filesystem. */
    interface IoSupplier<T> {
        T get() throws IOException;
    }
}
