// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Engine workspace-build request ({@code BuildService.buildWorkspace}). */
public record WorkspaceRequest(
        Path entryDir,
        JkBuild entryBuild,
        Path cache,
        Path jdksDir,
        int workers,
        String profile,
        boolean skipTests,
        boolean verbose,
        // 0 = auto; 1 = serial; N = window of N.
        int maxModuleConcurrency,
        // Pre-computed dirty modules, or null to forecast.
        Set<Path> dirtyHint,
        // False when the host already owns process-wide memory planning.
        boolean applyMemoryPlan,
        // True: freshen workspace lock before build; false: use pin verbatim (e.g. jk verify).
        boolean freshenLock,
        /**
         * When true, each module's plan is {@code testOnly} (parse → sync → compile main/test →
         * run-tests, no package) — same shape as {@code jk test}. HTTP/MCP {@code jk_test} uses this.
         */
        boolean testOnly,
        // Variant selection ("" / "release" / "release|tier=free").
        String variant,
        // Client shell env for env:-indirected plugin config.
        Map<String, String> clientEnv,
        /**
         * True for throwaway builds ({@code jk verify}'s scratch rebuild): action keys are salted
         * with a scratch path that can never recur, so tasks must not persist action-cache records
         * or incremental state — reads may still bypass per {@code rebuild}.
         */
        boolean ephemeralActions) {

    /** Defaults testOnly=false, variant empty, clientEnv empty. */
    public WorkspaceRequest(
            Path entryDir,
            JkBuild entryBuild,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            Set<Path> dirtyHint,
            boolean applyMemoryPlan,
            boolean freshenLock) {
        this(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                dirtyHint,
                applyMemoryPlan,
                freshenLock,
                false,
                "",
                Map.of(),
                false);
    }

    /** Pre-ephemeralActions canonical shape (defaults false — persistent caches). */
    public WorkspaceRequest(
            Path entryDir,
            JkBuild entryBuild,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            Set<Path> dirtyHint,
            boolean applyMemoryPlan,
            boolean freshenLock,
            boolean testOnly,
            String variant,
            Map<String, String> clientEnv) {
        this(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                dirtyHint,
                applyMemoryPlan,
                freshenLock,
                testOnly,
                variant,
                clientEnv,
                false);
    }

    /** This request with a variant selection + client-resolved env attached. */
    public WorkspaceRequest withVariant(String variant, Map<String, String> clientEnv) {
        return new WorkspaceRequest(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                dirtyHint,
                applyMemoryPlan,
                freshenLock,
                testOnly,
                variant == null ? "" : variant,
                clientEnv == null ? Map.of() : clientEnv,
                ephemeralActions);
    }

    /** Copy with {@link #testOnly()} set (HTTP/MCP {@code jk_test} true test-only path). */
    public WorkspaceRequest withTestOnly(boolean testOnly) {
        return new WorkspaceRequest(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                dirtyHint,
                applyMemoryPlan,
                freshenLock,
                testOnly,
                variant,
                clientEnv,
                ephemeralActions);
    }

    /** Copy with {@link #ephemeralActions()} set ({@code jk verify} scratch rebuild). */
    public WorkspaceRequest withEphemeralActions(boolean ephemeralActions) {
        return new WorkspaceRequest(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                dirtyHint,
                applyMemoryPlan,
                freshenLock,
                testOnly,
                variant,
                clientEnv,
                ephemeralActions);
    }
}
