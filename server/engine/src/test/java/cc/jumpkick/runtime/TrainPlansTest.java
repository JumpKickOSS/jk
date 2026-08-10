// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainPlansTest {

    @Test
    void train_plan_terminals_on_train_not_native(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "t"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.Main"

                [train]
                """);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildPlan plan = TrainPlans.moduleBuildPlan(
                dir,
                build,
                dir.resolve("cache"),
                null,
                null,
                Path.of(System.getProperty("java.home")),
                null,
                false,
                true,
                false);
        assertThat(plan.steps().stream().map(s -> s.name())).contains(TaskNames.TRAIN);
        assertThat(plan.steps().stream().map(s -> s.name())).doesNotContain(TaskNames.NATIVE_IMAGE);
        // Train requires package-jar
        var train = plan.steps().stream()
                .filter(s -> TaskNames.TRAIN.equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(train.requires()).contains(TaskNames.PACKAGE_JAR);
    }
}
