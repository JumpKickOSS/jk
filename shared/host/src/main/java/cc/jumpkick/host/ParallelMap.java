// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An I/O-throwing function over a list on the fork-join pool the caller runs in (the common pool
 * otherwise), results in input order. Short lists stay on the calling thread, where a fan-out
 * costs more than it saves.
 */
public final class ParallelMap {

    /** Below this many items the work stays on the calling thread. */
    public static final int THRESHOLD = 64;

    /** A function that reads or writes files. */
    @FunctionalInterface
    public interface IoFunction<T, R extends @Nullable Object> {
        R apply(T t) throws IOException;
    }

    private ParallelMap() {}

    /** {@code fn} over {@code items}, in order; the first {@link IOException} thrown is rethrown. */
    public static <T, R extends @Nullable Object> List<R> map(List<T> items, IoFunction<T, R> fn) throws IOException {
        if (items.size() < THRESHOLD) {
            List<R> out = new ArrayList<>(items.size());
            for (T t : items) out.add(fn.apply(t));
            return out;
        }
        try {
            return items.parallelStream()
                    .map(t -> {
                        try {
                            return fn.apply(t);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
