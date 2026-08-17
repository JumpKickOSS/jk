// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
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
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(null);
        assertThat(verb.jobKinds()).containsExactly("build", "assemble", "test");

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
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(null);
        String line = verb.decodeJob(
                new JobSpec("test", dir.toString(), List.of(), List.of("fast"), List.of("slow"), List.of(), false));
        var sel = cc.jumpkick.engine.protocol.ProtoJobs.testSelectionOf(line);
        assertThat(sel.includeTags()).containsExactly("fast");
        assertThat(sel.excludeTags()).containsExactly("slow");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void lock_and_update_decode_to_their_wire_requests(@TempDir Path dir) throws Exception {
        project(dir);
        String lock = new LockVerb(null).decodeJob(JobSpec.of("lock", dir.toString()));
        assertThat(EngineProtocol.typeOf(lock)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Jsonl.str(lock, "trigger")).isEqualTo("web");
        assertThat(Jsonl.bool(lock, "conservative", true)).isFalse();

        String update = new UpdateVerb(null).decodeJob(JobSpec.of("update", dir.toString()));
        assertThat(EngineProtocol.typeOf(update)).isEqualTo(EngineProtocol.UPDATE_REQUEST);
    }

    @Test
    void clean_decodes_to_the_registered_cache_clear_request(@TempDir Path dir) throws Exception {
        project(dir);
        String clean = new CacheMaintenanceVerb(null).decodeJob(JobSpec.of("clean", dir.toString()));
        assertThat(EngineProtocol.typeOf(clean)).isEqualTo(EngineProtocol.CACHE_PRUNE_REQUEST);
        assertThat(Jsonl.str(clean, "op")).isEqualTo("clear");
        assertThat(Jsonl.str(clean, "dir")).isEqualTo(dir.toString());
    }

    @Test
    void publish_install_and_import_decode_to_their_wire_requests(@TempDir Path dir) throws Exception {
        project(dir);
        // Detached publish is always a dry run — credential resolution never enters the engine.
        String publish = new PublishVerb(null).decodeJob(JobSpec.of("publish", dir.toString()));
        assertThat(EngineProtocol.typeOf(publish)).isEqualTo(EngineProtocol.PUBLISH_REQUEST);
        assertThat(Jsonl.bool(publish, "dryRun", false)).isTrue();
        assertThat(Jsonl.str(publish, "authType")).isEqualTo("anonymous");

        String install = new InstallVerb(null).decodeJob(JobSpec.of("install", dir.toString()));
        assertThat(EngineProtocol.typeOf(install)).isEqualTo(EngineProtocol.INSTALL_REQUEST);
        assertThat(Jsonl.str(install, "m2Dir")).endsWith("repository");

        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        String imp = new ImportVerb(null).decodeJob(JobSpec.of("import", dir.toString()));
        assertThat(EngineProtocol.typeOf(imp)).isEqualTo(EngineProtocol.IMPORT_REQUEST);
        assertThat(Jsonl.str(imp, "source")).endsWith("pom.xml");
        assertThat(Jsonl.str(imp, "out")).endsWith("jk.toml");
    }

    @Test
    void import_without_a_build_file_refuses_decode(@TempDir Path dir) throws Exception {
        project(dir);
        assertThatThrownBy(() -> new ImportVerb(null).decodeJob(JobSpec.of("import", dir.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no build file");
    }

    @Test
    void every_exposed_kind_resolves_through_the_registry() {
        VerbRegistry registry = VerbRegistry.standard(new EngineVerbBridgeStub());
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
        assertThatThrownBy(() -> new SyncVerb(null).decodeJob(JobSpec.of("sync", "/p")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not hosted");
    }

    /** decodeJob never touches the host; registry construction needs one non-null reference. */
    private static final class EngineVerbBridgeStub implements VerbHost {
        @Override
        public long eventRequestId() {
            return -1;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public cc.jumpkick.runtime.WorkspaceBuildListener workspaceListener(BufferedWriter w, String dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cc.jumpkick.run.BuildPlanListener planListener(
                String dir, BufferedWriter w, cc.jumpkick.run.BuildPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cc.jumpkick.run.BuildPlanListener planListener(
                String dir, BufferedWriter w, Function<cc.jumpkick.run.BuildPlanResult, String> enc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return false;
        }

        @Override
        public void accTests(long rid, cc.jumpkick.run.TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, BufferedWriter w, boolean force) {}

        @Override
        public void flushTimeline(long rid, BufferedWriter w) {}

        @Override
        public void send(BufferedWriter w, String line) {}

        @Override
        public void sendQuiet(BufferedWriter w, String line) {}

        @Override
        public String redactEnv(String dir, String text) {
            return text;
        }

        @Override
        public String requestFailedLine(String dir, Throwable e) {
            return "";
        }

        @Override
        public void publishRequestError(long rid, String dir, String message) {}

        @Override
        public cc.jumpkick.config.Session resolveSession(
                String requestLine, cc.jumpkick.config.Session.CancelToken cancel, boolean refresh) {
            return cc.jumpkick.config.Session.defaults();
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {}
    }
}
