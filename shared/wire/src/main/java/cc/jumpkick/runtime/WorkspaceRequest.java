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
        // Variant selection ("" / "release" / "release|tier=free").
        String variant,
        // Client shell env for env:-indirected plugin config.
        Map<String, String> clientEnv) {

    /** Defaults variant to empty and clientEnv to empty. */
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
                "",
                Map.of());
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
                variant == null ? "" : variant,
                clientEnv == null ? Map.of() : clientEnv);
    }
}
