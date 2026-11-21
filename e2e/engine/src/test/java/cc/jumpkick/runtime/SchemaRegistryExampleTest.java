// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.SchemaPresetExamplesTest.anyFile;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.build;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.example;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.lock;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.SchemaEntry;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Avro example with its schemas in a registry instead of the tree: {@code jk lock} pins the
 * subject and the one it references, the build generates and compiles from the pinned schemas, and
 * builds again once the registry is gone.
 *
 * <p>Network test (Maven Central, for the Avro compiler); the registry is a stub on loopback.
 */
@Tag("network")
class SchemaRegistryExampleTest {

    private static final String ROLE = "{\"type\": \"enum\", \"name\": \"Role\", \"namespace\": \"com.acme.events\","
            + " \"symbols\": [\"ADMIN\", \"USER\"]}";
    private static final String USER_CREATED = "{\"type\": \"record\", \"name\": \"UserCreated\", \"namespace\":"
            + " \"com.acme.events\", \"fields\": [{\"name\": \"id\", \"type\": \"long\"}, {\"name\": \"email\", \"type\":"
            + " \"string\"}, {\"name\": \"role\", \"type\": \"com.acme.events.Role\"}]}";

    @Test
    void the_example_builds_from_registry_subjects_and_again_with_the_registry_gone(@TempDir Path tmp)
            throws Exception {
        Map<String, String> served = Map.of(
                "/subjects/user-created-value/versions/latest",
                version(
                        "user-created-value",
                        2,
                        11,
                        USER_CREATED,
                        "{\"name\":\"com.acme.events.Role\",\"subject\":\"role\",\"version\":1}"),
                "/subjects/role/versions/1",
                version("role", 1, 4, ROLE, ""));
        HttpServer registry = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        registry.createContext("/", exchange -> {
            String body = served.get(exchange.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        registry.start();
        Path project = example(tmp, PluginJar.AVRO, "plugins/avro");
        Path cache = TestCaches.dir("avro-example-cache");
        try {
            PathUtil.deleteRecursivelyOrThrow(project.resolve("src/main/avro"));
            Files.writeString(
                    project.resolve("jk.toml"),
                    "\n[schema-registry]\nurl = \"http://127.0.0.1:"
                            + registry.getAddress().getPort()
                            + "\"\nsubjects = { \"user-created-value\" = \"latest\" }\n",
                    StandardOpenOption.APPEND);
            lock(project, cache);
            assertThat(LockfileReader.read(project.resolve("jk-lock.toml")).schemas())
                    .extracting(SchemaEntry::key)
                    .containsExactly("role@1", "user-created-value@2");

            BuildPlanResult first = build(project, cache);
            assertThat(first.errors()).isEmpty();
            assertThat(anyFile(project.resolve("target"), "UserCreated.class")).isTrue();
        } finally {
            registry.stop(0);
        }

        PathUtil.deleteRecursivelyOrThrow(project.resolve("target"));
        BuildPlanResult offline = build(project, cache);
        assertThat(offline.errors()).isEmpty();
        assertThat(anyFile(project.resolve("target"), "UserCreated.class")).isTrue();
    }

    private static String version(String subject, int version, long id, String schema, String reference) {
        return "{\"subject\":" + MiniJson.write(subject) + ",\"version\":" + version + ",\"id\":" + id + ",\"schema\":"
                + MiniJson.write(schema) + ",\"references\":[" + reference + "]}";
    }
}
