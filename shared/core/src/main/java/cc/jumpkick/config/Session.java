// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.task.IoLedger;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * Request-scoped context for one jk invocation: merged config, paths, JVM/JDK tuning, variant
 * selection, and cooperative cancellation. Per-request so concurrent builds in one JVM do not
 * clobber each other; threaded through the engine rather than process-global state.
 *
 * <p>Single-field copies are {@link With}-generated. Four are hand-written and say why, because
 * each one does something a generated wither cannot: {@link #withWorkingDir} absolutizes,
 * {@link #withJvm} maps {@code null} to {@link PluginTuning#NONE} where the canonical constructor
 * rejects it, and {@link #withVariant} / {@link #withToolchainSpecs} each set two components that
 * are only meaningful together. Every other normalisation lives in the canonical constructor, which
 * is what makes generating the rest safe — a generated wither runs it too.
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
        @With JkConfig config,
        Path workingDir,
        @With Path cacheDir,
        @With @Nullable Path jdksDir,
        PluginTuning jvm,
        @Nullable String jdkSpec,
        @Nullable String graalSpec,
        /**
         * The caller's {@code GRAALVM_HOME}, when they set one.
         *
         * <p>A home path, not a spec, which is why it cannot ride {@link #graalSpec}.@Nullable  It carries here * for the reason the specs do: the engine is a daemon, so a {@code System.getenv} inside it
         * answers from whichever shell started it, and a resident engine was picking the Graal that
         * shell knew about rather than the one the caller named.
         */
        @Nullable Path graalHome,
        @With boolean parallelTests,
        @With CancelToken cancel,
        // Variant selection + client-resolved env (env: indirection for signing secrets).
        String variant,
        @Nullable Map<String, String> clientEnv,
        /** CLI packaging override: empty, {@code fat}, or {@code minified} ({@code jk assemble --minified}). */
        @With String assemblyOverride,
        /** Test suite / tag selection ({@code jk test --suite}/tags); default = unit suite only. */
        @With TestSelection testSelection,
        /** {@code jk_run kind=test affected=true}: rank and run WIP test classes. */
        @With boolean affected,
        /** Cross-module changed-type carrier for {@code --affected}; shared by every copy. */
        @With AffectedChanged affectedChanged,
        /** Per-run byte accounting — one ledger per invocation, shared by every copy. */
        @With IoLedger io) {

    public Session {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(jvm, "jvm");
        cancel = (cancel == null) ? CancelToken.NONE : cancel;
        variant = (variant == null) ? "" : variant;
        clientEnv = (clientEnv == null || clientEnv.isEmpty()) ? Map.of() : Map.copyOf(clientEnv);
        assemblyOverride = (assemblyOverride == null || assemblyOverride.isBlank()) ? "" : assemblyOverride.trim();
        testSelection = testSelection == null ? TestSelection.DEFAULT : testSelection;
        affectedChanged = (affectedChanged == null) ? new AffectedChanged() : affectedChanged;
        io = (io == null) ? new IoLedger() : io;
    }

    /** A copy carrying the given variant selection + client-resolved env — two components, one fact. */
    public Session withVariant(@Nullable String variant, @Nullable Map<String, String> clientEnv) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                graalHome,
                parallelTests,
                cancel,
                variant == null ? "" : variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                affected,
                affectedChanged,
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
                null,
                false,
                CancelToken.live(),
                "",
                null,
                "",
                TestSelection.DEFAULT,
                false,
                new AffectedChanged(),
                IoLedger.currentOrNew());
    }

    /** A copy rooted at {@code dir}, absolutized — the working directory is never relative. */
    public Session withWorkingDir(Path dir) {
        return new Session(
                config,
                dir.toAbsolutePath().normalize(),
                cacheDir,
                jdksDir,
                jvm,
                jdkSpec,
                graalSpec,
                graalHome,
                parallelTests,
                cancel,
                variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                affected,
                affectedChanged,
                io);
    }

    /** A copy with the given JVM tuning ({@code null} → {@link PluginTuning#NONE}). */
    public Session withJvm(PluginTuning tuning) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                tuning == null ? PluginTuning.NONE : tuning,
                jdkSpec,
                graalSpec,
                graalHome,
                parallelTests,
                cancel,
                variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                affected,
                affectedChanged,
                io);
    }

    /**
     * The request's toolchain selection: {@code --jdk} / {@code --graal} (with the {@code JK_JDK} /
     * {@code JK_GRAAL} spellings folded in by the client), plus the caller's {@code GRAALVM_HOME}.
     * Blanks normalize to null.
     */
    public Session withToolchainSpecs(@Nullable String jdk, @Nullable String graal, @Nullable Path graalHome) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                jvm,
                blankToNull(jdk),
                blankToNull(graal),
                graalHome,
                parallelTests,
                cancel,
                variant,
                clientEnv,
                assemblyOverride,
                testSelection,
                affected,
                affectedChanged,
                io);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
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
