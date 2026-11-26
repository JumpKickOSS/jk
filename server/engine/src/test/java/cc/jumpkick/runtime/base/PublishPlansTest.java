// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.model.PomMetadata;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link PublishPlans#checkStep} chooses the module's main jar. */
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

        BuildPlan plan = planFor(dir, request());
        assertThat(plan.run().success()).isTrue();
        assertThat(plan.get(PublishPlans.JAR)).contains(dir.resolve("target/cli-1.0.0.jar"));
    }

    private static final PomMetadata SIGNS = PomMetadata.EMPTY;
    private static final Map<String, String> GITLAB = Map.of("SIGSTORE_ID_TOKEN", "jwt");

    private static PublishPlans.Request requestWithKey(Path key) {
        return new PublishPlans.Request(null, false, false, key, null, RepoCredential.ANONYMOUS, false);
    }

    @Test
    void a_key_file_signs_with_pgp_and_ci_adds_keyless() {
        PublishPlans.Signing signing =
                PublishPlans.Signing.of(SIGNS, requestWithKey(Path.of("release.asc")), GITLAB::get);
        assertThat(signing.pgpKey()).isEqualTo(Path.of("release.asc"));
        assertThat(signing.keyless()).isTrue();
        assertThat(signing.unsigned()).isFalse();
        assertThat(signing.sbom()).isTrue();
        assertThat(signing.provenance()).isTrue();
    }

    @Test
    void with_no_key_and_no_identity_the_release_is_unsigned() {
        PublishPlans.Signing signing = PublishPlans.Signing.of(
                SIGNS,
                new PublishPlans.Request(null, false, false, null, null, RepoCredential.ANONYMOUS, false),
                n -> null);
        assertThat(signing.unsigned()).isTrue();
    }

    @Test
    void sign_false_signs_nothing_whatever_the_key_and_the_identity() {
        PomMetadata off = new PomMetadata(
                null, null, null, List.of(), List.of(), null, null, false, null, false, true, true, false);
        PublishPlans.Signing signing =
                PublishPlans.Signing.of(off, requestWithKey(Path.of("release.asc")), GITLAB::get);
        assertThat(signing.pgpKey()).isNull();
        assertThat(signing.keyless()).isFalse();
        assertThat(signing.unsigned()).isFalse();
    }

    private static PublishPlans.Request request() {
        return new PublishPlans.Request(null, false, true, null, null, new RepoCredential.Anonymous(), false);
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
