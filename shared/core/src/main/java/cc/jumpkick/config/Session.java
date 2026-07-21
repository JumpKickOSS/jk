// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Request-scoped context for one jk invocation: merged config, paths, JVM/JDK tuning, variant
 * selection, and cooperative cancellation. Per-request so concurrent builds in one JVM do not
 * clobber each other; threaded through the engine rather than process-global state.
 *
 * @param jdksDir JDK install root, or {@code null} for {@link JkDirs#jdks()}
 * @param jdkSpec top-tier JDK selection ({@code --jdk}), or {@code null}
 * @param graalSpec top-tier GraalVM selection ({@code --graal}), or {@code null}
 * @param parallelTests when false, module tests serialize through the engine's test gate
 * @param cancel never null after construction; {@code null} input becomes {@link CancelToken#NONE}
 */
public record Session(
        JkConfig config,
        Path workingDir,
        Path cacheDir,
        Path jdksDir,
        PluginTuning jvm,
        String jdkSpec,
        String graalSpec,
        boolean parallelTests,
        CancelToken cancel,
        // Variant selection + client-resolved env (env: indirection for signing secrets).
        String variant,
        java.util.Map<String, String> clientEnv) {

    public Session {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(jvm, "jvm");
        cancel = (cancel == null) ? CancelToken.NONE : cancel;
        variant = (variant == null) ? "" : variant;
        clientEnv = (clientEnv == null || clientEnv.isEmpty()) ? java.util.Map.of() : java.util.Map.copyOf(clientEnv);
    }

    /** A copy carrying the given variant selection + client-resolved env. */
    public Session withVariant(String variant, java.util.Map<String, String> clientEnv) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv);
    }

    /**
     * Cooperative cancellation for one session. Front-end calls {@link #cancel()}; engine polls
     * {@link #cancelled()}. Thread-safe.
     */
    public interface CancelToken {

        /** Whether cancellation has been requested. */
        boolean cancelled();

        /** Request cancellation. Idempotent; safe to call from any thread. */
        void cancel();

        /** A shared, inert token: {@link #cancelled()} is always {@code false} and {@link #cancel()} is a no-op. */
        CancelToken NONE = new CancelToken() {
            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public void cancel() {
                // no-op: NONE is never cancellable
            }
        };

        /** A fresh, live token backed by an {@link AtomicBoolean} — thread-safe and one-shot. */
        static CancelToken live() {
            return new CancelToken() {
                private final AtomicBoolean flag = new AtomicBoolean(false);

                @Override
                public boolean cancelled() {
                    return flag.get();
                }

                @Override
                public void cancel() {
                    flag.set(true);
                }
            };
        }
    }

    /**
     * A default session: empty config, current working directory, default cache/JDK roots, no tuning,
     * and a fresh {@link CancelToken#live() live} cancellation token so a front-end can cancel it.
     */
    public static Session defaults() {
        return new Session(
                JkConfig.empty(),
                Path.of("").toAbsolutePath().normalize(),
                JkDirs.cache(),
                null,
                PluginTuning.NONE,
                null,
                null,
                false,
                CancelToken.live(),
                "",
                null);
    }

    public Session withConfig(JkConfig newConfig) {
        return new Session(
                newConfig,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv);
    }

    public Session withWorkingDir(Path dir) {
        return new Session(
                config,
                dir.toAbsolutePath().normalize(),
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv);
    }

    public Session withCacheDir(Path dir) {
        return new Session(
                config, workingDir, dir, jdksDir, jvm, jdkSpec, graalSpec, parallelTests, cancel, variant, clientEnv);
    }

    public Session withJdksDir(Path dir) {
        return new Session(
                config, workingDir, cacheDir, dir, jvm, jdkSpec, graalSpec, parallelTests, cancel, variant, clientEnv);
    }

    public Session withJvm(PluginTuning tuning) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                tuning == null ? PluginTuning.NONE : tuning,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv);
    }

    /** The top-tier JDK / GraalVM selection ({@code --jdk} / {@code --graal}); blanks normalize to null. */
    public Session withToolchainSpecs(String jdk, String graal) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                blankToNull(jdk),
                blankToNull(graal),
                parallelTests,
                cancel,
                variant,
                clientEnv);
    }

    public Session withParallelTests(boolean enabled) {
        return new Session(
                config, workingDir, cacheDir, jdksDir, jvm, jdkSpec, graalSpec, enabled, cancel, variant, clientEnv);
    }

    /** A copy carrying the given cancellation token ({@code null} → {@link CancelToken#NONE}). */
    public Session withCancel(CancelToken token) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                parallelTests,
                token,
                variant,
                clientEnv);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** JDK install root, resolving the default when unset. */
    public Path jdksRoot() {
        return jdksDir != null ? jdksDir : JkDirs.jdks();
    }

    // ---- convenience delegators onto config -------------------------------

    public boolean offline() {
        return config.offlineOr(false);
    }

    public boolean force() {
        return config.forceOr(false);
    }

    public boolean quiet() {
        return config.quietOr(false);
    }

    public boolean verbose() {
        return config.verboseOr(false);
    }

    /** Whether this session's {@link #cancel() cancellation token} has been signalled. */
    public boolean cancelled() {
        return cancel.cancelled();
    }
}
