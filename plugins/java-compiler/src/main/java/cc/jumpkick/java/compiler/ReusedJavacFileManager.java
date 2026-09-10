// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.jspecify.annotations.Nullable;

/**
 * One {@link StandardJavaFileManager} per thread, held across compiles rather than opened and closed
 * around each one.
 *
 * <p>A manager opened per compile re-opens and re-indexes every classpath entry, and that cost is
 * fixed per invocation — it does not shrink with the module. Measured on a 20-cpu host, a
 * <em>one-source</em> compile against 200 real jars takes 364 ms on Windows and 138 ms on Linux,
 * against 23 ms and 21 ms with an empty classpath: roughly 1.7 ms per jar on Windows against 0.6 ms
 * on Linux. Holding the manager keeps those jars open, so the next compile pays only for entries it
 * has not seen. A compile whose classpath shares 190 of its 200 entries with its predecessor drops
 * from 276 ms to 56 ms.
 *
 * <p>Reuse is bounded by the job, not by the process being immortal: the worker's pull loop stays up
 * across the modules of one job and exits on {@code DONE}, so no manager outlives the build that
 * created it. Within a job, javac re-resolves the classpath on every task, so a directory entry
 * whose contents changed since the last compile is seen — a class added to an upstream output
 * directory resolves, and one removed stops resolving. {@code ReusedJavacFileManagerTest} pins both.
 *
 * <p>Per thread rather than static because the worker is single-threaded today but the protocol is
 * specified to allow concurrent items; {@link StandardJavaFileManager} is not thread-safe, so a
 * second compile thread must get its own rather than silently share this one.
 */
final class ReusedJavacFileManager {

    private static final ThreadLocal<ReusedJavacFileManager> PER_THREAD =
            ThreadLocal.withInitial(ReusedJavacFileManager::new);

    /**
     * The listener the manager is constructed with. A manager outlives any one compile, so it cannot
     * be handed that compile's collector directly — it forwards to whichever compile is running.
     */
    private final Relay relay = new Relay();

    private @Nullable StandardJavaFileManager fm;
    private @Nullable Charset encoding;

    private ReusedJavacFileManager() {}

    /**
     * The calling thread's manager, reporting file-manager diagnostics to {@code diags} until the
     * next call. A manager built for a different charset is discarded rather than reused, because
     * the charset a manager is constructed with is what decodes sources and cannot be changed after.
     *
     * <p>{@code declaresProcessorPath} says whether the compile about to run names its own
     * {@code -processorpath}. If it does not, and the held manager still carries one, that manager is
     * thrown away rather than handed over — see {@link #staleProcessorPath}.
     */
    static StandardJavaFileManager acquire(
            JavaCompiler javac,
            Charset encoding,
            DiagnosticListener<JavaFileObject> diags,
            boolean declaresProcessorPath) {
        return PER_THREAD.get().get(javac, encoding, diags, declaresProcessorPath);
    }

    private StandardJavaFileManager get(
            JavaCompiler javac,
            Charset wanted,
            DiagnosticListener<JavaFileObject> diags,
            boolean declaresProcessorPath) {
        relay.to = diags;
        StandardJavaFileManager current = fm;
        if (current != null && wanted.equals(encoding) && !staleProcessorPath(current, declaresProcessorPath)) {
            return current;
        }
        close(current);
        StandardJavaFileManager fresh = javac.getStandardFileManager(relay, Locale.ROOT, wanted);
        fm = fresh;
        encoding = wanted;
        return fresh;
    }

    /**
     * Whether the held manager carries a processor path the compile about to run never asked for.
     *
     * <p>Such a manager has to be discarded rather than corrected, because the location cannot be put
     * back to "never set": clearing it with a null leaves javac treating the processor path as
     * declared and empty, which stops it falling back to the compile classpath for {@code -proc:full}
     * discovery. Leaving it alone instead is worse — the compile would silently run the previous
     * module's processors and generate code into a build that never asked for any. Discarding costs
     * one manager on a transition between a module with processors and one without, and nothing on a
     * run of either kind.
     */
    private static boolean staleProcessorPath(StandardJavaFileManager held, boolean declaresProcessorPath) {
        return !declaresProcessorPath && held.hasLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH);
    }

    /**
     * Closing releases the open jars, which is the whole point of holding it, so this runs only when
     * a manager is being replaced. A manager still open when the worker exits is left to the process
     * teardown, which is force-kill — the handles must be safe to leak, and they are.
     */
    private void close(@Nullable StandardJavaFileManager stale) {
        if (stale == null) return;
        try {
            stale.close();
        } catch (IOException e) {
            // A manager being thrown away cannot fail the compile that is replacing it.
        }
    }

    private static final class Relay implements DiagnosticListener<JavaFileObject> {

        private volatile @Nullable DiagnosticListener<JavaFileObject> to;

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            DiagnosticListener<JavaFileObject> target = to;
            if (target != null) target.report(diagnostic);
        }
    }
}
