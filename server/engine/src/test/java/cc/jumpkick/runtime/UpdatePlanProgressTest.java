// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk update} plan tick budget: ~10% preflight, bulk on resolve, thin write trailer. */
class UpdatePlanProgressTest {

    @Test
    void update_plan_reserves_about_ten_percent_for_preflight(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"
                """);
        List<Lockfile.Artifact> arts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            arts.add(new Lockfile.Artifact(
                    "com.example:lib" + i,
                    "1.0.0",
                    "central+https://repo1.maven.org/maven2/",
                    "sha256:" + String.format("%064d", i),
                    null,
                    List.of(Scope.MAIN),
                    List.of()));
        }
        LockfileWriter.write(
                new Lockfile(1, "jk test", Lockfile.RESOLUTION_ALGORITHM, null, null, arts, List.of(), List.of()),
                dir.resolve("jk-lock.toml"));

        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildPlan plan = LockPlans.updateBuildPlan(
                dir, build, dir.resolve("cache"), null, List.of(), true, null, ResolveObserver.NOOP);

        Task parse = step(plan, TaskNames.PARSE_BUILD);
        Task resolve = step(plan, TaskNames.RESOLVE_DEPS);
        Task write = step(plan, TaskNames.WRITE_LOCKFILE);

        int preflight = parse.estimateTicks();
        int resolveTicks = resolve.estimateTicks();
        int writeTicks = write.estimateTicks();
        int total = preflight + resolveTicks + writeTicks;

        assertThat(resolveTicks).isGreaterThan(preflight);
        assertThat(writeTicks).isEqualTo(1);
        double preflightShare = (double) preflight / total;
        assertThat(preflightShare).isBetween(0.08, 0.12);
    }

    private static Task step(BuildPlan plan, String name) {
        return plan.steps().stream()
                .filter(t -> name.equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing step " + name));
    }
}
