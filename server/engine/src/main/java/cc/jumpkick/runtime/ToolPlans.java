// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.ToolCoordSpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolResolver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Tool-resolution plan for {@code jk tool install/run} and {@code jk install <g:a:v>}.
 * Launcher write and exec of the resolved {@link ToolEnv} stay client-side.
 */
public final class ToolPlans {

    private ToolPlans() {}

    /** The resolved tool env, populated by the {@code resolve-coord} step. */
    public static final BuildPlanKey<ToolEnv> TOOL_ENV = BuildPlanKey.scalar("tool-env", ToolEnv.class);

    /**
     * Build the single-step resolve plan for {@code spec}.
     *
     * @param spec the tool coordinate — pinned {@code g:a:v} or floating {@code g:a[@selector]}
     *     (the floating pick against maven-metadata happens inside the step)
     * @param withSpecs {@code --with} extras injected into the env's resolution (may be empty)
     * @param mainClassOverride the {@code --main} override, or {@code null} to read the primary
     *     jar's manifest
     * @param repoUrl overrides Maven Central ({@code null} = Central)
     * @param coordLabel the preformatted coordinate for the step label — the CLI's in-process path
     *     passes its themed {@code Coords.gav}, the engine passes the plain spec so no pre-themed
     *     text ever crosses the wire
     */
    public static BuildPlan resolveBuildPlan(
            ToolCoordSpec spec,
            List<ToolCoordSpec> withSpecs,
            @Nullable String bin,
            @Nullable String mainClassOverride,
            @Nullable URI repoUrl,
            Path cache,
            String coordLabel) {
        Task resolve = Task.builder(TaskNames.RESOLVE_COORD)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve " + coordLabel);
                    Cas cas = JkStores.storeCas();
                    URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
                    RepoGroup repos = RepoGroup.of(new MavenRepo(RepositorySpec.CENTRAL, url, new Http(), cas));
                    try {
                        ctx.put(TOOL_ENV, new ToolResolver(repos).resolve(spec, bin, mainClassOverride, withSpecs));
                    } catch (RuntimeException | IOException e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("tool-resolve")
                .stateKeys(TOOL_ENV)
                .addTask(resolve)
                .build();
    }
}
