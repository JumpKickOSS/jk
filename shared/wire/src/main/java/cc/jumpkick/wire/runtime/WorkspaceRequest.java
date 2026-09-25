// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Engine workspace-build request ({@code BuildService.buildWorkspace}).
 *
 * <p>The engine always parses {@code jk.toml} from {@link #entryDir()}. Clients send directory,
 * flags, and optional module-selector tokens — never a parsed model.
 */
public record WorkspaceRequest(
        Path entryDir,
        Path cache,
        @Nullable Path jdksDir,
        int workers,
        @Nullable String profile,
        boolean skipTests,
        boolean verbose,
        // 0 = auto; 1 = serial; N = window of N.
        int maxModuleConcurrency,
        // Pre-computed dirty modules, or null to forecast.
        @Nullable Set<Path> dirtyHint,
        // False when the host already owns process-wide memory planning.
        boolean applyMemoryPlan,
        // True: freshen workspace lock before build; false: use pin verbatim (e.g. jk verify).
        boolean freshenLock,
        /**
         * When true, each module's plan is {@code testOnly} (parse → sync → compile main/test →
         * run-tests, no package) — same shape as {@code jk test}. HTTP/MCP {@code test} uses this.
         */
        boolean testOnly,
        // Variant selection ("" / "release" / "release|tier=free").
        @Nullable String variant,
        // Client shell env for env:-indirected plugin config.
        @Nullable Map<String, String> clientEnv,
        /**
         * True for throwaway builds ({@code jk verify}'s scratch rebuild): action keys are salted
         * with a scratch path that can never recur, so tasks must not persist action-cache records
         * or incremental state — reads may still bypass per {@code rebuild}.
         */
        boolean ephemeralActions,
        /**
         * Target basket + optional module cone. Default {@link WorkspaceSpec#DEFAULT} is package /
         * whole graph — same as historical {@code jk build}.
         */
        @Nullable WorkspaceSpec spec,
        /**
         * {@code -m}/{@code --affected-since} tokens ({@code affected:<ref>} prefix). Empty: no
         * client filter. The engine resolves these via {@code ModuleSelection} / {@code JobSelect}.
         */
        @Nullable List<String> modules,
        /**
         * Finish every module that can run instead of stopping at the first failure. The run still
         * fails; what changes is how much it tells you before it does. Admission already keys on a
         * unit's artifacts being ready rather than on its completion, so a module whose TESTS
         * failed has already published what its dependents compile against and they can proceed —
         * one whose packaging failed publishes nothing and they fail on their own account.
         */
        boolean keepGoing) {

    public WorkspaceRequest {
        if (spec == null) spec = WorkspaceSpec.DEFAULT;
        if (modules == null) modules = List.of();
        else modules = List.copyOf(modules);
        if (variant == null) variant = "";
        if (clientEnv == null) clientEnv = Map.of();
    }

    /** Defaults testOnly=false, variant empty, clientEnv empty, no module selectors. */
    public WorkspaceRequest(
            Path entryDir,
            Path cache,
            @Nullable Path jdksDir,
            int workers,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            @Nullable Set<Path> dirtyHint,
            boolean applyMemoryPlan,
            boolean freshenLock) {
        this(
                entryDir,
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
                false,
                WorkspaceSpec.DEFAULT,
                List.of(),
                false);
    }

    /** Pre-ephemeralActions canonical shape (defaults false — persistent caches). */
    public WorkspaceRequest(
            Path entryDir,
            Path cache,
            @Nullable Path jdksDir,
            int workers,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            @Nullable Set<Path> dirtyHint,
            boolean applyMemoryPlan,
            boolean freshenLock,
            boolean testOnly,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv) {
        this(
                entryDir,
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
                false,
                WorkspaceSpec.DEFAULT,
                List.of(),
                false);
    }

    /** This request with a variant selection + client-resolved env attached. */
    public WorkspaceRequest withVariant(@Nullable String variant, @Nullable Map<String, String> clientEnv) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec,
                modules,
                keepGoing);
    }

    /** Copy with {@link #testOnly()} set (HTTP/MCP {@code test} true test-only path). */
    public WorkspaceRequest withTestOnly(boolean testOnly) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec,
                modules,
                keepGoing);
    }

    /** Copy with {@link #ephemeralActions()} set ({@code jk verify} scratch rebuild). */
    public WorkspaceRequest withEphemeralActions(boolean ephemeralActions) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec,
                modules,
                keepGoing);
    }

    /** Copy with target / selection (native, image, compile, …). */
    public WorkspaceRequest withSpec(@Nullable WorkspaceSpec spec) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec == null ? WorkspaceSpec.DEFAULT : spec,
                modules,
                keepGoing);
    }

    /** Copy with {@code -m}/{@code --affected-since} selector tokens. */
    public WorkspaceRequest withModules(@Nullable List<String> modules) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec,
                modules == null ? List.of() : modules,
                keepGoing);
    }

    /** Finish every module that can run; report all failures instead of stopping at the first. */
    public WorkspaceRequest withKeepGoing(boolean keepGoing) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec,
                modules,
                keepGoing);
    }

    /**
     * Replace the within-module test-worker request. Used once per workspace build to turn the
     * {@code 0 = auto} spelling into this graph's fair share during resource seeding, before any
     * module is planned, so the memory plan and every module plan agree on one number.
     */
    public WorkspaceRequest withWorkers(int workers) {
        return new WorkspaceRequest(
                entryDir,
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
                ephemeralActions,
                spec,
                modules,
                keepGoing);
    }

    /** Effective target: explicit spec, else TEST when {@link #testOnly}. */
    public WorkspaceTarget target() {
        WorkspaceSpec s = spec == null ? WorkspaceSpec.DEFAULT : spec;
        return s.effectiveTarget(testOnly);
    }
}
