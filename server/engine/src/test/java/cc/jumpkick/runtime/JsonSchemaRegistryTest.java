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
import cc.jumpkick.testing.TestCaches;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSON Schema subjects from a registry: {@code jk lock} pins a subject and the one its {@code $ref}
 * names, jsonschema2pojo generates both with the reference resolving to the pinned file, and the
 * module builds again once the registry is gone.
 *
 * <p>Network test (Maven Central, for jsonschema2pojo and Jackson); the registry is a stub on loopback.
 */
@Tag("network")
class JsonSchemaRegistryTest {

    private static final String MONEY =
            "{\"type\": \"object\", \"title\": \"Money\", \"properties\": {\"cents\": {\"type\": \"integer\"}}}";
    private static final String ORDER = "{\"type\": \"object\", \"title\": \"Order\", \"properties\": {\"id\":"
            + " {\"type\": \"integer\"}, \"total\": {\"$ref\": \"money.json\"}}}";

    @Test
    void registry_subjects_generate_with_their_ref_and_again_with_the_registry_gone(@TempDir Path tmp)
            throws Exception {
        Map<String, String> served = Map.of(
                "/subjects/orders-value/versions/latest",
                version("orders-value", 4, 21, ORDER, "{\"name\":\"money.json\",\"subject\":\"money\",\"version\":1}"),
                "/subjects/money/versions/1",
                version("money", 1, 9, MONEY, ""));
        HttpServer registry = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        registry.createContext("/", exchange -> {
            String body = served.get(exchange.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        registry.start();
        Path project = example(tmp, PluginJar.JSON_SCHEMA, "plugins/json-schema");
        Path cache = TestCaches.dir("json-schema-example-cache");
        try {
            PathUtil.deleteRecursivelyOrThrow(project.resolve("src/main/resources/json"));
            Files.writeString(
                    project.resolve("jk.toml"),
                    Files.readString(project.resolve("jk.toml"))
                                    .replace(
                                            "package = \"com.acme.events\"",
                                            "package = \"com.acme.events\"\ntitle-as-classname = true")
                            + "\n[schema-registry]\nurl = \"http://127.0.0.1:"
                            + registry.getAddress().getPort()
                            + "\"\nsubjects = { \"orders-value\" = \"latest\" }\n");
            Files.writeString(project.resolve("src/main/java/com/acme/Main.java"), """
                    package com.acme;

                    import com.acme.events.Money;
                    import com.acme.events.Order;

                    public final class Main {
                        private Main() {}

                        public static void main(String[] args) {
                            Money total = new Money();
                            total.setCents(5);
                            Order order = new Order();
                            order.setTotal(total);
                            System.out.println(order.getTotal().getCents());
                        }
                    }
                    """);
            lock(project, cache);
            assertThat(LockfileReader.read(project.resolve("jk-lock.toml")).schemas())
                    .extracting(SchemaEntry::key)
                    .containsExactly("money@1", "orders-value@4");

            assertThat(build(project, cache).errors()).isEmpty();
            assertThat(anyFile(project.resolve("target"), "Order.class")).isTrue();
            assertThat(anyFile(project.resolve("target"), "Money.class")).isTrue();
        } finally {
            registry.stop(0);
        }

        PathUtil.deleteRecursivelyOrThrow(project.resolve("target"));
        assertThat(build(project, cache).errors()).isEmpty();
        assertThat(anyFile(project.resolve("target"), "Main.class")).isTrue();
    }

    private static String version(String subject, int version, long id, String schema, String reference) {
        return "{\"subject\":" + MiniJson.write(subject) + ",\"version\":" + version + ",\"id\":" + id
                + ",\"schemaType\":\"JSON\",\"schema\":" + MiniJson.write(schema) + ",\"references\":[" + reference
                + "]}";
    }
}
