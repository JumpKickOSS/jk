// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;

import cc.jumpkick.runtime.base.BuildLogicAnchor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The input tokens one module's build keys its build logic on, one lazy walk per scope.
 *
 * <p>A module anchor keys on the module ({@link BuildLogicSupport#projectInputTokens}); a
 * root anchor and the guard's tree lane key on the whole checkout ({@link
 * BuildLogicSupport#workspaceInputTokens}). A standalone project runs both over the same
 * directory in one build, so the two walks are held apart: a reader at either scope gets that
 * scope's tokens, whichever walked first. Each scope walks at most once per build; a scope nothing
 * consults never walks. One instance per module per build, never reused across builds.
 */
final class BuildLogicInputTokens {

    private final AtomicReference<@Nullable List<String>> project = new AtomicReference<>();
    private final AtomicReference<@Nullable List<String>> workspace = new AtomicReference<>();

    /** The tokens for a task at {@code anchor}: the module's for a module anchor, the checkout's for a root one. */
    List<String> forAnchor(Path projectDir, BuildLogicAnchor anchor) throws IOException {
        return anchor.workspaceScoped() ? workspace(projectDir) : project(projectDir);
    }

    /** The module scope's tokens, walked on first use. */
    List<String> project(Path projectDir) throws IOException {
        List<String> tokens = project.get();
        if (tokens != null) return tokens;
        project.compareAndSet(null, BuildLogicSupport.projectInputTokens(projectDir));
        return requireNonNull(project.get());
    }

    /** The workspace scope's tokens, walked on first use. */
    List<String> workspace(Path rootDir) throws IOException {
        List<String> tokens = workspace.get();
        if (tokens != null) return tokens;
        workspace.compareAndSet(null, BuildLogicSupport.workspaceInputTokens(rootDir));
        return requireNonNull(workspace.get());
    }
}
