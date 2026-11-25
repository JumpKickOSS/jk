// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link PublishPlans#checkStep} chooses the thin jar by default and validates {@code [publish] assembly}. */
class PublishPlansTest {

    private @Nullable String savedProp;
    private Session savedSession = SessionContext.current();

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        savedProp = System.getProperty(PluginJar.PUBLISHER.jarProperty());
        savedSession = SessionContext.current();
        Path stubWorker = Files.writeString(tmp.resolve("jk-publisher.jar"), "stub");
        System.setProperty(PluginJar.PUBLISHER.jarProperty(), stubWorker.toString());
        SessionContext.install(savedSession.withRelease(true));
    }

    @AfterEach
    void tearDown() {
        SessionContext.install(savedSession);
        if (savedProp == null) {
            System.clearProperty(PluginJar.PUBLISHER.jarProperty());
        } else {
            System.setProperty(PluginJar.PUBLISHER.jarProperty(), savedProp);
        }
    }

    @Test
    void assembly_module_publishes_thin_jar_as_main_artifact(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "cli"
                version = "1.0.0"

                [application]
                main     = "com.example.Main"
                assembly = true
                """);

        BuildPlan plan = planFor(dir, request(null));
        assertThat(plan.run().success()).isTrue();
        assertThat(plan.get(PublishPlans.JAR)).contains(dir.resolve("target/cli-1.0.0.jar"));
    }

    @Test
    void explicit_jar_override_wins(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "cli"
                version = "1.0.0"

                [application]
                main     = "com.example.Main"
                assembly = true
                """);
        Path custom = dir.resolve("custom.jar");

        BuildPlan plan = planFor(dir, request(custom));
        assertThat(plan.run().success()).isTrue();
        assertThat(plan.get(PublishPlans.JAR)).contains(custom);
    }

    @Test
    void publish_assembly_without_module_assembly_is_refused(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"

                [publish]
                assembly = true
                """);

        BuildPlan plan = planFor(dir, request(null));
        BuildPlanResult result = plan.run();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message())
                .contains("[publish] assembly = true needs [application] assembly = true or [library]"));
    }

    private static PublishPlans.Request request(@Nullable Path jarOverride) {
        return new PublishPlans.Request(
                null,
                null,
                null,
                jarOverride,
                false,
                true,
                null,
                null,
                false,
                false,
                false,
                new RepoCredential.Anonymous(),
                false,
                null);
    }

    private static BuildPlan planFor(Path dir, PublishPlans.Request req) {
        return BuildPlan.builder("publish-check")
                .stateKeys(PublishPlans.PROJECT, PublishPlans.JAR)
                .addTask(Task.builder(TaskNames.PARSE_BUILD)
                        .stage(BuildStage.RESOLVE)
                        .ticks(1)
                        .execute(ctx -> {})
                        .build())
                .addTask(PublishPlans.checkStep(dir, req))
                .terminal(TaskNames.PUBLISH_CHECK)
                .build();
    }
}
