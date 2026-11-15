// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobRequests;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoJobs;
import cc.jumpkick.wire.protocol.UpdateRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP/MCP job kinds decode into the same wire request lines the CLI sends. */
class HostedVerbDecodeJobTest {

    private static Path project(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                """);
        return dir;
    }

    @Test
    void build_test_and_assemble_decode_to_build_requests(@TempDir Path dir) throws Exception {
        project(dir);
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());
        assertThat(verb.jobKinds())
                .containsExactly(
                        "build",
                        "assemble",
                        "test",
                        "gate",
                        "guard",
                        "compile",
                        "test-compile",
                        "image",
                        "install",
                        "native",
                        "publish",
                        "upload",
                        "sync");

        String build = verb.decodeJob(JobSpec.of("build", dir.toString()));
        assertThat(EngineProtocol.typeOf(build)).isEqualTo(EngineProtocol.BUILD_REQUEST);
        assertThat(Jsonl.str(build, "dir")).isEqualTo(dir.toString());
        // The verb decodes the job; the admission point stamps who asked.
        assertThat(Jsonl.str(build, "trigger")).isNull();
        assertThat(Jsonl.bool(build, "skipTests", false)).isFalse();
        assertThat(Jsonl.bool(build, "testOnly", false)).isFalse();

        String assemble = verb.decodeJob(JobSpec.of("assemble", dir.toString()));
        assertThat(Jsonl.bool(assemble, "skipTests", false)).isTrue();

        String test = verb.decodeJob(JobSpec.of("test", dir.toString()));
        assertThat(Jsonl.bool(test, "testOnly", false)).isTrue();
        // The decoded line refines the journal kind: a workspace test job is kind "test" on
        // every surface.
        assertThat(verb.toJobRequest(test).verb()).isEqualTo("test");
        assertThat(verb.toJobRequest(build).verb()).isEqualTo("build");

        String testCompile = verb.decodeJob(JobSpec.of("test-compile", dir.toString()));
        assertThat(Jsonl.str(testCompile, "workspaceTarget")).isEqualTo("test_compile");
        assertThat(Jsonl.bool(testCompile, "skipTests", false))
                .as("its tests compile")
                .isFalse();
        assertThat(verb.toJobRequest(testCompile).verb())
                .as("journaled as a compile")
                .isEqualTo("compile");
    }

    @Test
    void test_selection_rides_the_decoded_line(@TempDir Path dir) throws Exception {
        project(dir);
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());
        String line = verb.decodeJob(new JobSpec(
                "test",
                dir.toString(),
                List.of(),
                List.of("fast"),
                List.of("slow"),
                List.of(),
                List.of("RangeTest"),
                false,
                false,
                null,
                List.of(),
                false,
                JobOrigin.WEB));
        var sel = ProtoJobs.testSelectionOf(line);
        assertThat(sel.includeTags()).containsExactly("fast");
        assertThat(sel.classes()).containsExactly("RangeTest");
        assertThat(sel.excludeTags()).containsExactly("slow");
    }

    /** {@code kind=gate} is {@code jk test -g}: the guard suites with their lanes and tests, journaled as a guard run. */
    @Test
    void a_gate_job_runs_the_guard_suites_with_their_tests(@TempDir Path dir) throws Exception {
        project(dir);
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());
        String gate = verb.decodeJob(JobSpec.of("gate", dir.toString()));
        var sel = ProtoJobs.testSelectionOf(gate);
        assertThat(sel.guard()).isTrue();
        assertThat(sel.suites()).containsExactlyElementsOf(TestSuites.GUARD_SUITES);
        assertThat(Jsonl.bool(gate, "skipTests", false)).isFalse();
        assertThat(Jsonl.bool(gate, "testOnly", false)).isTrue();
        assertThat(verb.toJobRequest(gate).verb()).isEqualTo("guard");

        String guard = verb.decodeJob(JobSpec.of("guard", dir.toString()));
        assertThat(ProtoJobs.testSelectionOf(guard).guard()).isTrue();
        assertThat(Jsonl.bool(guard, "skipTests", false))
                .as("jk guard runs no tests")
                .isTrue();
        assertThat(verb.toJobRequest(guard).verb()).isEqualTo("guard");
    }

    @Test
    void an_update_job_carries_its_deps_and_major(@TempDir Path dir) throws Exception {
        project(dir);
        JobSpec spec = JobRequests.decode(
                Map.of("dir", dir.toString(), "kind", "update", "deps", List.of("jackson"), "major", true),
                JobOrigin.WEB,
                "build",
                null,
                Set.of());
        UpdateRequest req = UpdateRequest.decode(new UpdateVerb(new InertVerbHost()).decodeJob(spec));
        assertThat(req.deps()).containsExactly("jackson");
        assertThat(req.major()).isTrue();
        assertThat(req.preview()).isFalse();
        assertThatThrownBy(() -> JobRequests.decode(
                        Map.of("dir", dir.toString(), "kind", "build", "deps", List.of("jackson")),
                        JobOrigin.WEB,
                        "build",
                        null,
                        Set.of()))
                .hasMessageContaining("kind=update");
    }

    @Test
    void lock_and_update_decode_to_their_wire_requests(@TempDir Path dir) throws Exception {
        project(dir);
        String lock = new LockVerb(new InertVerbHost()).decodeJob(JobSpec.of("lock", dir.toString()));
        assertThat(EngineProtocol.typeOf(lock)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Jsonl.str(lock, "trigger")).isNull();
        assertThat(Jsonl.bool(lock, "freshen", true)).isFalse();

        String update = new UpdateVerb(new InertVerbHost()).decodeJob(JobSpec.of("update", dir.toString()));
        assertThat(EngineProtocol.typeOf(update)).isEqualTo(EngineProtocol.UPDATE_REQUEST);
    }

    @Test
    void clean_decodes_to_the_registered_cache_clear_request(@TempDir Path dir) throws Exception {
        project(dir);
        String clean = new CacheMaintenanceVerb(new InertVerbHost()).decodeJob(JobSpec.of("clean", dir.toString()));
        assertThat(EngineProtocol.typeOf(clean)).isEqualTo(EngineProtocol.CACHE_PRUNE_REQUEST);
        assertThat(Jsonl.str(clean, "op")).isEqualTo("clear");
        assertThat(Jsonl.str(clean, "dir")).isEqualTo(dir.toString());
    }

    @Test
    void publish_install_and_import_decode_to_their_wire_requests(@TempDir Path dir) throws Exception {
        project(dir);
        // Detached publish is always a dry run — credential resolution never enters the engine.
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());
        String publish = verb.decodeJob(JobSpec.of("publish", dir.toString()));
        assertThat(EngineProtocol.typeOf(publish)).isEqualTo(EngineProtocol.BUILD_REQUEST);
        assertThat(Jsonl.str(publish, "workspaceTarget")).isEqualTo("publish");
        String tail = Jsonl.nested(publish, "tail");
        assertThat(Jsonl.bool(tail, "dryRun", false)).isTrue();
        assertThat(Jsonl.str(tail, "authType")).isEqualTo("anonymous");

        String install = verb.decodeJob(JobSpec.of("install", dir.toString()));
        assertThat(EngineProtocol.typeOf(install)).isEqualTo(EngineProtocol.BUILD_REQUEST);
        assertThat(Jsonl.str(install, "workspaceTarget")).isEqualTo("install");

        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        String imp = new ImportVerb(new InertVerbHost()).decodeJob(JobSpec.of("import", dir.toString()));
        assertThat(EngineProtocol.typeOf(imp)).isEqualTo(EngineProtocol.IMPORT_REQUEST);
        assertThat(Jsonl.str(imp, "source")).endsWith("pom.xml");
        assertThat(Jsonl.str(imp, "out")).endsWith("jk.toml");
    }

    @Test
    void an_upload_job_goes_to_the_repository_publish_names(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"

                [repositories.releases]
                url = "http://127.0.0.1:18080/repo/"
                allow-insecure = true

                [publish]
                repository = "releases"
                """);
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());

        String upload = verb.decodeJob(JobSpec.of("upload", dir.toString()));

        assertThat(Jsonl.str(upload, "workspaceTarget")).isEqualTo("publish");
        String tail = Jsonl.nested(upload, "tail");
        assertThat(Jsonl.bool(tail, "dryRun", false)).isFalse();
        assertThat(Jsonl.str(tail, "repoUrl")).isEqualTo("http://127.0.0.1:18080/repo/");
    }

    @Test
    void an_upload_job_without_a_publish_target_is_refused_with_the_fix(@TempDir Path dir) throws Exception {
        project(dir);
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());

        assertThatThrownBy(() -> verb.decodeJob(JobSpec.of("upload", dir.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[publish]")
                .hasMessageContaining("repository");
    }

    @Test
    void import_without_a_build_file_refuses_decode(@TempDir Path dir) throws Exception {
        project(dir);
        assertThatThrownBy(() -> new ImportVerb(new InertVerbHost()).decodeJob(JobSpec.of("import", dir.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no build file");
    }

    @Test
    void every_exposed_kind_resolves_through_the_registry() {
        VerbRegistry registry = VerbRegistry.standard(new InertVerbHost(), new DecisionQueue());
        for (String kind : List.of(
                "build",
                "assemble",
                "test",
                "lock",
                "update",
                "format",
                "compile",
                "image",
                "native",
                "clean",
                "publish",
                "install",
                "import")) {
            assertThat(registry.forJobKind(kind)).as(kind).isNotNull();
        }
        assertThat(registry.forJobKind("quux")).isNull();
    }

    @Test
    void an_unexposed_verb_refuses_decode() {
        assertThatThrownBy(() -> new GitFetchVerb(new InertVerbHost()).decodeJob(JobSpec.of("git-fetch", "/p")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not hosted");
    }
}
