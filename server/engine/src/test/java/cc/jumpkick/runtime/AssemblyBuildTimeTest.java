// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine lib's assembly carries the commit time of the checkout it was packaged from as {@code
 * Build-Time} and its commit as {@code Build-Commit}, so two engines of one version are ordered and
 * told apart by their source rather than by when a jar was copied. The client's assembly carries them
 * only in a release build of a clean checkout, as its native image does: a snapshot client's key
 * names no commit, so an edit elsewhere in the tree does not repackage it. Every other assembly, and
 * a checkout git does not describe, carries no such attribute. The stamp is part of the assembly's
 * key, so a warm action cache never restores another commit's stamp.
 */
class AssemblyBuildTimeTest {

    private static final Instant COMMITTED = Instant.parse("2026-09-17T15:57:16Z");
    private static final GitFetcher.Worktree WORKTREE =
            new GitFetcher.Worktree("4ee07400a592deadbeef", "main", COMMITTED, false, Optional.empty());

    private static JkBuild productLib() {
        return JkBuild.builder(new Project("cc.jumpkick", "jk-engine", "0.13.7", 25))
                .install(new JkBuild.Install("jk-engine", null))
                .build();
    }

    private static JkBuild productBin() {
        return productBin("0.13.7");
    }

    private static JkBuild productBin(String version) {
        return JkBuild.builder(new Project("cc.jumpkick", "jk-cli", version, 25))
                .install(new JkBuild.Install(null, "jk"))
                .build();
    }

    @Test
    void the_product_lib_assembly_is_stamped_with_the_commit_time_and_commit() {
        Map<String, String> attrs = PackagingKeys.assemblyAttributes(productLib(), Optional.of(WORKTREE));
        assertThat(attrs)
                .containsEntry(BuildIdentity.BUILD_TIME_ATTRIBUTE, "2026-09-17T15:57:16Z")
                .containsEntry(BuildIdentity.BUILD_COMMIT_ATTRIBUTE, "4ee07400a592deadbeef");
    }

    @Test
    void a_release_client_assembly_of_a_clean_checkout_is_stamped() {
        assertThat(PackagingKeys.assemblyAttributes(productBin(), Optional.of(WORKTREE)))
                .containsEntry(BuildIdentity.BUILD_TIME_ATTRIBUTE, "2026-09-17T15:57:16Z")
                .containsEntry(BuildIdentity.BUILD_COMMIT_ATTRIBUTE, "4ee07400a592deadbeef");
    }

    @Test
    void a_snapshot_or_dirty_client_assembly_carries_no_commit() {
        GitFetcher.Worktree dirty =
                new GitFetcher.Worktree("4ee07400a592deadbeef", "main", COMMITTED, true, Optional.empty());
        assertThat(PackagingKeys.assemblyAttributes(productBin("0.13.7-SNAPSHOT"), Optional.of(WORKTREE)))
                .doesNotContainKeys(BuildIdentity.BUILD_TIME_ATTRIBUTE, BuildIdentity.BUILD_COMMIT_ATTRIBUTE);
        assertThat(PackagingKeys.assemblyAttributes(productBin(), Optional.of(dirty)))
                .as("a dirty tree is no release")
                .doesNotContainKeys(BuildIdentity.BUILD_TIME_ATTRIBUTE, BuildIdentity.BUILD_COMMIT_ATTRIBUTE);
    }

    @Test
    void a_snapshot_client_key_holds_when_the_checkout_moves_or_is_edited(@TempDir Path module) {
        JkBuild client = productBin("0.13.7-SNAPSHOT");
        GitFetcher.Worktree next = new GitFetcher.Worktree(
                "5ff18511b6a3deadbeef", "main", COMMITTED.plusSeconds(3600), false, Optional.empty());
        GitFetcher.Worktree edited =
                new GitFetcher.Worktree("4ee07400a592deadbeef", "main", COMMITTED, true, Optional.empty());

        assertThat(assemblyKey(module, client, next)).isEqualTo(assemblyKey(module, client, WORKTREE));
        assertThat(assemblyKey(module, client, edited))
                .as("an edit the client does not contain does not repackage it")
                .isEqualTo(assemblyKey(module, client, WORKTREE));
        assertThat(assemblyKey(module, productLib(), edited))
                .as("the engine lib still names the edited checkout")
                .isNotEqualTo(assemblyKey(module, productLib(), WORKTREE));
    }

    @Test
    void an_ordinary_application_and_an_undescribed_checkout_carry_no_build_time() {
        JkBuild app = JkBuild.builder(new Project("com.example", "app", "1.0.0", 25))
                .manifest(Map.of("Implementation-Title", "app"))
                .build();
        assertThat(PackagingKeys.assemblyAttributes(app, Optional.of(WORKTREE)))
                .containsOnly(Map.entry("Implementation-Title", "app"));
        assertThat(PackagingKeys.assemblyAttributes(productLib(), Optional.empty()))
                .doesNotContainKeys(BuildIdentity.BUILD_TIME_ATTRIBUTE, BuildIdentity.BUILD_COMMIT_ATTRIBUTE);
    }

    @Test
    void the_product_lib_key_follows_the_commit_and_an_ordinary_key_does_not(@TempDir Path module) {
        GitFetcher.Worktree next = new GitFetcher.Worktree(
                "5ff18511b6a3deadbeef", "main", COMMITTED.plusSeconds(3600), false, Optional.empty());
        JkBuild app = JkBuild.builder(new Project("com.example", "app", "1.0.0", 25))
                .manifest(Map.of("Implementation-Title", "app"))
                .build();

        assertThat(assemblyKey(module, productLib(), WORKTREE)).isNotEqualTo(assemblyKey(module, productLib(), next));
        assertThat(assemblyKey(module, productLib(), WORKTREE))
                .as("one commit keys one assembly")
                .isEqualTo(assemblyKey(module, productLib(), WORKTREE));
        assertThat(assemblyKey(module, app, WORKTREE)).isEqualTo(assemblyKey(module, app, next));
    }

    private static String assemblyKey(Path module, JkBuild project, GitFetcher.Worktree worktree) {
        return PackagingKeys.assembly(
                        module.resolve("target/app.jar"),
                        module,
                        project,
                        PackagingKeys.assemblyAttributes(project, Optional.of(worktree)),
                        "classes",
                        "contrib",
                        "deps",
                        null)
                .key();
    }
}
