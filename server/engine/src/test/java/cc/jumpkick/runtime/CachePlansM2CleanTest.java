// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.BuildPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Removing jk's downloads from the Maven local repository: on request, never on its own unless an admin says so. */
class CachePlansM2CleanTest {

    private static final String REL = "com/example/lib/1.0/lib-1.0.jar";

    @TempDir
    Path tmp;

    private Path store;
    private Path m2;
    private String priorStore;

    @BeforeEach
    void setUp() throws Exception {
        store = tmp.resolve("store");
        m2 = tmp.resolve("m2");
        priorStore = System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        System.setProperty("jk.m2.local", m2.toString());
        downloadedLongAgo();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jk.m2.local");
        System.clearProperty("jk.m2.auto-clean");
        if (priorStore == null) System.clearProperty("jk.env.JK_STORE_DIR");
        else System.setProperty("jk.env.JK_STORE_DIR", priorStore);
    }

    private void downloadedLongAgo() throws IOException {
        RepoArtifactStore central =
                RepoArtifactStore.forRepository(store, RepositorySpec.CENTRAL, RepositorySpec.MAVEN_CENTRAL.url());
        Path source = Files.writeString(tmp.resolve("dl.jar"), "old-bytes");
        central.materialize(REL, source, Hashing.sha256Hex(source), true);
        Files.setLastModifiedTime(
                ArtifactMemo.jkPath(store.resolve("repos/central"), REL),
                FileTime.fromMillis(System.currentTimeMillis() - 90L * 86_400_000L));
    }

    @Test
    void the_mcp_action_previews_then_removes_on_confirm() {
        Map<String, Object> preview = McpMachine.m2Clean(false, 60, null, null);
        assertThat(preview).containsEntry("preview", true).containsEntry("files", 1);
        assertThat(m2.resolve(REL)).exists();

        Map<String, Object> done = McpMachine.m2Clean(true, 60, null, null);

        assertThat(done).containsEntry("cleaned", true).containsEntry("files", 1);
        assertThat(m2.resolve(REL)).doesNotExist();
    }

    @Test
    void a_project_lockfile_holds_back_what_it_pins() throws Exception {
        String sha = Hashing.sha256Hex(m2.resolve(REL));
        Path project = Files.createDirectories(tmp.resolve("project"));
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        List.of(new Lockfile.Artifact(
                                "com.example:lib",
                                "1.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:" + sha,
                                null,
                                List.of(Scope.MAIN),
                                List.of()))),
                project.resolve("jk-lock.toml"));

        Map<String, Object> reply = McpMachine.m2Clean(true, 60, project, null);

        assertThat(reply).containsEntry("files", 0);
        assertThat(m2.resolve(REL)).exists();
    }

    @Test
    void the_plan_a_cli_request_runs_removes_and_reports_files_and_bytes() {
        BuildPlan plan = CachePlans.m2CleanBuildPlan(null, 60, false);

        assertThat(plan.run().success()).isTrue();

        assertThat(plan.get(CachePlans.FILES)).contains(1L);
        assertThat(plan.get(CachePlans.BYTES)).contains((long) "old-bytes".length());
        assertThat(m2.resolve(REL)).doesNotExist();
    }

    @Test
    void the_idle_prune_leaves_the_local_repository_alone_unless_auto_clean_is_on() {
        Path cache = tmp.resolve("cache");

        assertThat(CachePlans.pruneBuildPlan(cache, false, true).run().success())
                .isTrue();
        assertThat(m2.resolve(REL)).as("off by default").exists();

        System.setProperty("jk.m2.auto-clean", "true");
        assertThat(CachePlans.pruneBuildPlan(cache, false, true).run().success())
                .isTrue();
        assertThat(m2.resolve(REL)).as("an admin asked").doesNotExist();
    }
}
