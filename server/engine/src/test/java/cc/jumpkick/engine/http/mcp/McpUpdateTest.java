// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.verbs.Updates;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code update}: a preview lists the pin moves, the majors left and the packages each move adds,
 * without writing; apply starts the {@code update} job {@code jk update} runs, as the agent. Candidates come from the project's own declared
 * repositories.
 */
@Tag("integration")
class McpUpdateTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolated;

    @BeforeEach
    void isolate() {
        System.setProperty("jk.env.JK_STORE_DIR", isolated.resolve("store").toString());
        System.setProperty("jk.env.JK_CACHE_DIR", isolated.resolve("cache").toString());
    }

    @AfterEach
    void release() {
        LockfileReader.clearCache();
    }

    @Test
    void preview_reports_the_moves_and_the_majors_left_without_writing(@TempDir Path dir) throws Exception {
        project(dir);
        Map<String, Object> preview = Updates.structured(Updates.preview(dir.toString(), List.of(), false));
        assertThat(rows(preview, "rewrites"))
                .extracting(r -> r.get("handle"), r -> r.get("from"), r -> r.get("to"))
                .containsExactlyInAnyOrder(tuple("jackson", "2.18.0", "2.18.2"), tuple("other", "1.0.0", "1.1.0"));
        assertThat(rows(preview, "available"))
                .as("a newer major is reported, not planned")
                .extracting(r -> r.get("handle"), r -> r.get("to"))
                .containsExactly(tuple("jackson", "3.0.0"));
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("com.acme:jackson:2.18.0");
        assertThat(dir.resolve("jk-lock.toml")).doesNotExist();

        Map<String, Object> major = Updates.structured(Updates.preview(dir.toString(), List.of("jackson"), true));
        assertThat(rows(major, "rewrites")).extracting(r -> r.get("to")).containsExactly("3.0.0");
        assertThat(rows(major, "available")).isEmpty();
    }

    @Test
    void the_tool_previews_by_default_and_apply_starts_the_update_job_as_the_agent(@TempDir Path dir) throws Exception {
        project(dir);
        List<JobSpec> admitted = new ArrayList<>();
        EngineHttpJobs jobs = new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                admitted.add(spec);
                return 1L;
            }

            @Override
            public boolean cancel(long requestId) {
                return false;
            }

            @Override
            public int cancelDir(String d) {
                return 0;
            }
        };
        McpHandler mcp = new McpHandler(
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
                jobs,
                d -> Map.of(),
                List::of,
                "0.12.0");
        String reply = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"update\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"deps\":[\"other\"]}}}");
        assertThat(reply).contains("\"type\":\"update\"").contains("\"to\":\"1.1.0\"");
        assertThat(reply).doesNotContain("2.18.2");
        assertThat(admitted).as("a preview starts no job").isEmpty();
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("com.acme:other:1.0.0");

        String applied = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"update\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"deps\":[\"other\"],\"apply\":true,\"wait\":false}}}");
        assertThat(applied).contains("RUNNING update jid=1");
        assertThat(admitted).singleElement().satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo("update");
            assertThat(spec.deps()).containsExactly("other");
            assertThat(spec.major()).isFalse();
            assertThat(spec.origin().trigger()).isEqualTo("mcp");
        });
    }

    @Test
    void a_preview_names_the_packages_a_move_adds_to_the_lock(@TempDir Path dir) throws Exception {
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.leaf("com.acme", "extra", "1.0");
        upstream.pom("com.acme", "growing", "1.0.0", MavenStub.emptyPom("com.acme", "growing", "1.0.0"));
        upstream.pom("com.acme", "growing", "1.0.1", """
                <project><groupId>com.acme</groupId><artifactId>growing</artifactId><version>1.0.1</version>
                  <dependencies><dependency>
                    <groupId>com.acme</groupId><artifactId>extra</artifactId><version>1.0</version>
                  </dependency></dependencies>
                </project>""");
        upstream.metadata("com.acme", "growing", "1.0.0", "1.0.1");
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "%s"

                [dependencies]
                growing = "com.acme:growing:1.0.0"
                """.formatted(http.baseUrl()));
        LockFlow.Result first = LockFlow.run(dir, isolated.resolve("cache"), List.of(), false, null);
        assertThat(first.status()).as("the first lock: %s", first.error()).isZero();

        Map<String, Object> preview = Updates.structured(Updates.preview(dir.toString(), List.of(), false));

        assertThat(rows(preview, "rewrites")).singleElement().satisfies(row -> {
            assertThat(row.get("to")).isEqualTo("1.0.1");
            assertThat(String.valueOf(row.get("adds"))).contains("com.acme:extra");
        });
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("com.acme:growing:1.0.0");
    }

    @Test
    void a_preview_counts_the_places_that_use_api_a_move_takes_away(@TempDir Path dir) throws Exception {
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        for (String v : List.of("1.0.0", "1.1.0")) {
            upstream.pomOnly("com.acme", "text", v, MavenStub.emptyPom("com.acme", "text", v));
            upstream.bytes(MavenStub.path("com.acme", "text", v, ".jar"), textJar(v.equals("1.0.0")));
        }
        upstream.metadata("com.acme", "text", "1.0.0", "1.1.0");
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "%s"

                [dependencies]
                text = "com.acme:text:1.0.0"
                """.formatted(http.baseUrl()));
        LockFlow.Result first = LockFlow.run(dir, isolated.resolve("cache"), List.of(), false, null);
        assertThat(first.status()).as("the first lock: %s", first.error()).isZero();
        Files.createDirectories(dir.resolve("src/main/java/app"));
        Files.writeString(dir.resolve("src/main/java/app/App.java"), "package app;\n");
        Path classes = Files.createDirectories(dir.resolve("target/classes/app"));
        Files.write(classes.resolve("App.class"), ClassFile.of().build(ClassDesc.of("app.App"), c -> {
            c.withFlags(ClassFile.ACC_PUBLIC);
            c.with(SourceFileAttribute.of("App.java"));
            c.withMethodBody("run", ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
                b.lineNumber(7);
                b.ldc("x");
                b.invokestatic(ClassDesc.of("acme.Text"), "trim", TRIM);
                b.pop();
                b.return_();
            });
        }));

        Map<String, Object> preview = Updates.structured(Updates.preview(dir.toString(), List.of(), false));

        assertThat(rows(preview, "rewrites")).singleElement().satisfies(row -> {
            assertThat(row.get("to")).isEqualTo("1.1.0");
            assertThat(row.get("breaks"))
                    .as("App.run calls the trim 1.1.0 drops")
                    .isEqualTo(1);
        });
    }

    private static final MethodTypeDesc TRIM = MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String);

    /** A jar holding {@code acme.Text}, with its {@code trim} or without it. */
    private static byte[] textJar(boolean trim) throws IOException {
        byte[] text = ClassFile.of().build(ClassDesc.of("acme.Text"), c -> {
            c.withFlags(ClassFile.ACC_PUBLIC);
            if (trim)
                c.withMethodBody("trim", TRIM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b.aload(0)
                        .areturn());
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("acme/Text.class"));
            zip.write(text);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    // ---- fixture ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) requireNonNull(data.get(key));
    }

    private void project(Path dir) throws IOException {
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        publish(upstream, "jackson", "2.18.0", "2.18.2", "3.0.0");
        publish(upstream, "other", "1.0.0", "1.1.0");
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = { version = "25" }
                java    = 25

                [repositories]
                central = "%s"

                [dependencies]
                jackson = "com.acme:jackson:2.18.0"
                other = "com.acme:other:1.0.0"
                """.formatted(http.baseUrl()));
    }

    private static void publish(MavenStub upstream, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom("com.acme", artifact, v, MavenStub.emptyPom("com.acme", artifact, v));
        }
        upstream.metadata("com.acme", artifact, versions);
    }
}
