// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.task.IoLedger;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Request-scoped context for one jk invocation: merged config, paths, JVM/JDK tuning, variant
 * selection, and cooperative cancellation. Per-request so concurrent builds in one JVM do not
 * clobber each other; threaded through the engine rather than process-global state.
 *
 * @param jdksDir JDK install root, or {@code null} for {@link JkDirs#jdks}
 * @param jdkSpec top-tier JDK selection ({@code --jdk}), or {@code null}
 * @param graalSpec top-tier GraalVM selection ({@code --graal}), or {@code null}
 * @param parallelTests when false, module tests serialize through the engine's test gate
 * @param cancel never null after construction; {@code null} input becomes {@link CancelToken#NONE}
 * @param testSelection suite/tag selection for {@code jk test}+)
 * @param io per-run byte accounting (network + local cache); shared by every copy of this session
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
        java.util.Map<String, String> clientEnv,
        /** CLI packaging override: empty, {@code fat}, or {@code minified} ({@code jk assemble --minified}). */
        String assemblyOverride,
        /** Test suite / tag selection ({@code jk test --suite}/tags); default = unit suite only. */
        TestSelection testSelection,
        /** Per-run byte accounting — one ledger per invocation, shared by every copy. */
        IoLedger io) {

    public Session {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(jvm, "jvm");
        cancel = (cancel == null) ? CancelToken.NONE : cancel;
        variant = (variant == null) ? "" : variant;
        clientEnv = (clientEnv == null || clientEnv.isEmpty()) ? java.util.Map.of() : java.util.Map.copyOf(clientEnv);
        assemblyOverride = (assemblyOverride == null || assemblyOverride.isBlank()) ? "" : assemblyOverride.trim();
        testSelection = testSelection == null ? TestSelection.DEFAULT : testSelection;
        io = (io == null) ? new IoLedger() : io;
    }

    private Session copy(
            JkConfig config,
            Path workingDir,
            Path cacheDir,
            Path jdksDir,
            PluginTuning jvm,
            String jdkSpec,
            String graalSpec,
            boolean parallelTests,
            CancelToken cancel,
            String variant,
            java.util.Map<String, String> clientEnv,
            String assemblyOverride,
            TestSelection testSelection,
            IoLedger io) {
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    /** A copy carrying the given variant selection + client-resolved env. */
    public Session withVariant(String variant, java.util.Map<String, String> clientEnv) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    /**
     * Cooperative cancellation for one session. Front-end calls {@link #cancel}; engine polls
     * {@link #cancelled}. Thread-safe.
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
     * and a fresh {@link CancelToken#live live} cancellation token so a front-end can cancel it.
     * Its {@link IoLedger} is the run's ambient one when a request is open on this thread (the engine
     * opens one per request), else a fresh detached ledger.
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
                null,
                "",
                TestSelection.DEFAULT,
                IoLedger.currentOrNew());
    }

    public Session withConfig(JkConfig newConfig) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    public Session withWorkingDir(Path dir) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    public Session withCacheDir(Path dir) {
        return copy(
                config,
                workingDir,
                dir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    public Session withJdksDir(Path dir) {
        return copy(
                config,
                workingDir,
                cacheDir,
                dir,
                jvm,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    public Session withJvm(PluginTuning tuning) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    /** The top-tier JDK / GraalVM selection ({@code --jdk} / {@code --graal}); blanks normalize to null. */
    public Session withToolchainSpecs(String jdk, String graal) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    public Session withParallelTests(boolean enabled) {
        return copy(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                enabled,
                cancel,
                variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    /**
     * CLI packaging override for this invocation only ({@code fat} / {@code minified} / empty).
     * Does not rewrite {@code jk.toml}.
     */
    public Session withAssemblyOverride(String mode) {
        return copy(
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
                clientEnv,
                mode,
                testSelection,
                io);
    }

    /** A copy carrying the given cancellation token ({@code null} → {@link CancelToken#NONE}). */
    public Session withCancel(CancelToken token) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                io);
    }

    /** Suite / tag selection for this invocation ({@code jk test}). */
    public Session withTestSelection(TestSelection selection) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                selection == null ? TestSelection.DEFAULT : selection,
                io);
    }

    /**
     * A copy metering into {@code ledger} ({@code null} → a fresh one). Rarely needed: a session
     * built inside a request already adopts the run's ledger via {@link #defaults}.
     */
    public Session withIo(IoLedger ledger) {
        return copy(
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
                clientEnv,
                assemblyOverride,
                testSelection,
                ledger == null ? new IoLedger() : ledger);
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
