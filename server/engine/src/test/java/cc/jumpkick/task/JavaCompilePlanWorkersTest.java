// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ETA priced beside a build may not ask a compiler worker for Zinc's plan: those workers are
 * compiling the build. Under {@link JavaCompile.PlanWorkers#NONE} a compile with an analysis on
 * disk is predicted from its prior record, and no plan is attempted.
 */
class JavaCompilePlanWorkersTest {

    @Test
    void no_plan_workers_predicts_from_the_prior_record(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package p; class C {}");
        Path stateDir = Files.createDirectories(dir.resolve("state"));
        Files.writeString(stateDir.resolve(ProducerAnalyses.ANALYSIS_FILE), "not a real analysis");
        // A worker jar that cannot start: any attempt to plan on it fails with "no plan: …".
        Path workerJar = Files.writeString(dir.resolve("worker.jar"), "not a jar");
        CompileRequest request =
                new CompileRequest(List.of(src), List.of(), dir.resolve("classes"), 21, List.of(), null, List.of());
        ActionCache cache = new ActionCache(new Cas(dir.resolve("cas")), dir.resolve("actions"));

        JavaCompile.Prediction p = ScopedValue.where(JavaCompile.PLAN_WORKERS, JavaCompile.PlanWorkers.NONE)
                .call(() -> JavaCompile.predict(
                        "compile-java", request, "test", cache, stateDir, workerJar, WorkerEnv.strict()));

        assertThat(p.reason()).doesNotStartWith("no plan").isEqualTo("no prior compile record");
        assertThat(p.outcome()).isEqualTo(JavaCompile.Outcome.FULL);
    }
}
