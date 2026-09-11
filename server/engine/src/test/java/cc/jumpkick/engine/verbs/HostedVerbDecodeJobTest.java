// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertThat(verb.jobKinds()).containsExactly("build", "assemble", "test", "guard");

        String build = verb.decodeJob(JobSpec.of("build", dir.toString()));
        assertThat(EngineProtocol.typeOf(build)).isEqualTo(EngineProtocol.BUILD_REQUEST);
        assertThat(Jsonl.str(build, "dir")).isEqualTo(dir.toString());
        assertThat(Jsonl.str(build, "trigger")).isEqualTo("web");
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
    }

    @Test
    void test_selection_rides_the_decoded_line(@TempDir Path dir) throws Exception {
        project(dir);
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());
        String line = verb.decodeJob(new JobSpec(
                "test", dir.toString(), List.of(), List.of("fast"), List.of("slow"), List.of(), false, false));
        var sel = ProtoJobs.testSelectionOf(line);
        assertThat(sel.includeTags()).containsExactly("fast");
        assertThat(sel.excludeTags()).containsExactly("slow");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void lock_and_update_decode_to_their_wire_requests(@TempDir Path dir) throws Exception {
        project(dir);
        String lock = new LockVerb(new InertVerbHost()).decodeJob(JobSpec.of("lock", dir.toString()));
        assertThat(EngineProtocol.typeOf(lock)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Jsonl.str(lock, "trigger")).isEqualTo("web");
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
        String publish = new PublishVerb(new InertVerbHost()).decodeJob(JobSpec.of("publish", dir.toString()));
        assertThat(EngineProtocol.typeOf(publish)).isEqualTo(EngineProtocol.PUBLISH_REQUEST);
        assertThat(Jsonl.bool(publish, "dryRun", false)).isTrue();
        assertThat(Jsonl.str(publish, "authType")).isEqualTo("anonymous");

        String install = new InstallVerb(new InertVerbHost()).decodeJob(JobSpec.of("install", dir.toString()));
        assertThat(EngineProtocol.typeOf(install)).isEqualTo(EngineProtocol.INSTALL_REQUEST);
        assertThat(Jsonl.str(install, "m2Dir")).endsWith("repository");

        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        String imp = new ImportVerb(new InertVerbHost()).decodeJob(JobSpec.of("import", dir.toString()));
        assertThat(EngineProtocol.typeOf(imp)).isEqualTo(EngineProtocol.IMPORT_REQUEST);
        assertThat(Jsonl.str(imp, "source")).endsWith("pom.xml");
        assertThat(Jsonl.str(imp, "out")).endsWith("jk.toml");
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
        VerbRegistry registry = VerbRegistry.standard(new InertVerbHost());
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
        assertThatThrownBy(() -> new SyncVerb(new InertVerbHost()).decodeJob(JobSpec.of("sync", "/p")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not hosted");
    }
}
