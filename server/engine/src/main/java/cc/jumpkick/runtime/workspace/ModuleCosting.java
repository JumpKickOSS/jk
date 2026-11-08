// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.run.JkThreads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;

/**
 * Prices the modules of one estimate concurrently, results in module order. A module the forecast
 * left without steps (a fully cached plan priced as a rebuild, {@code jk explain}'s full-rebuild
 * figure) is priced by planning its pipeline, tens of milliseconds a module; one after another that
 * was eleven seconds on a 223-module workspace. Each module's price reads only shared, read-only
 * history, so they run side by side.
 *
 * <p>On the shared CPU pool ({@link JkThreads#cpu()}), whose platform threads carry the caller's
 * context: planning reads directories, a native call the virtual-thread scheduler does not
 * compensate for. A caller already on that pool prices serially, so a pool thread never waits on
 * tasks queued behind it.
 */
@NullMarked
final class ModuleCosting {

    /** Below this many modules per lane a lane costs more than it saves. */
    private static final int MODULES_PER_LANE = 4;

    private ModuleCosting() {}

    static <M, C> List<C> each(List<M> modules, Function<M, C> price) {
        int lanes = Math.min(JkThreads.CPU_THREADS / 2, modules.size() / MODULES_PER_LANE);
        if (lanes <= 1 || Thread.currentThread() instanceof ForkJoinWorkerThread) {
            List<C> out = new ArrayList<>(modules.size());
            for (M m : modules) out.add(price.apply(m));
            return out;
        }
        int chunk = (modules.size() + lanes - 1) / lanes;
        List<Future<List<C>>> pending = new ArrayList<>(lanes);
        for (int from = 0; from < modules.size(); from += chunk) {
            List<M> slice = modules.subList(from, Math.min(from + chunk, modules.size()));
            pending.add(JkThreads.cpu().submit(() -> {
                List<C> priced = new ArrayList<>(slice.size());
                for (M m : slice) priced.add(price.apply(m));
                return priced;
            }));
        }
        List<C> out = new ArrayList<>(modules.size());
        for (Future<List<C>> f : pending) out.addAll(join(f));
        return out;
    }

    private static <C> C join(Future<C> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("eta interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        }
    }
}
