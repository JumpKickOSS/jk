// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.SchemaPresetExamplesTest.anyFile;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.SchemaEntry;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protobuf subjects from a registry: {@code jk lock} pins a subject and the one it imports by path,
 * protoc compiles both with the well-known type it also imports coming from protobuf-java, and the
 * module builds again once the registry is gone.
 *
 * <p>Network test (Maven Central, for protoc and protobuf-java); the registry is a stub on loopback.
 */
@Tag("slow")
class ProtobufRegistryTest {

    private static final String MONEY = """
            syntax = "proto3";
            package acme;
            option java_package = "com.acme.pb";
            option java_multiple_files = true;
            message Money { int64 cents = 1; }
            """;
    private static final String ORDER = """
            syntax = "proto3";
            package acme;
            import "common/money.proto";
            import "google/protobuf/timestamp.proto";
            option java_package = "com.acme.pb";
            option java_multiple_files = true;
            message Order { Money total = 1; google.protobuf.Timestamp at = 2; }
            """;

    @Test
    void registry_subjects_compile_with_protoc_and_again_with_the_registry_gone(@TempDir Path tmp) throws Exception {
        Map<String, String> served = Map.of(
                "/subjects/orders-value/versions/latest",
                version(
                        "orders-value",
                        5,
                        102,
                        ORDER,
                        "{\"name\":\"common/money.proto\",\"subject\":\"money\",\"version\":2}"),
                "/subjects/money/versions/2",
                version("money", 2, 8, MONEY, ""));
        HttpServer registry = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        registry.createContext("/", exchange -> {
            String body = served.get(exchange.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        registry.start();
        Path project = Files.createDirectories(tmp.resolve("orders"));
        Path cache = TestCaches.dir("android-spike-cache");
        Files.writeString(
                project.resolve("jk.toml"), """
                name    = "orders"
                group   = "com.acme"
                version = "1.0.0"
                java    = 25

                [protobuf]
                version = "4.33.1"

                [schema-registry]
                url      = "http://127.0.0.1:%d"
                subjects = { "orders-value" = "latest" }

                [dependencies]
                protobuf-java = { group = "com.google.protobuf", name = "protobuf-java", version = "=4.33.1" }

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(registry.getAddress().getPort()));
        Path src = Files.createDirectories(project.resolve("src/com/acme/app"));
        Files.writeString(src.resolve("Main.java"), """
                package com.acme.app;

                import com.acme.pb.Money;
                import com.acme.pb.Order;

                public final class Main {
                    public static void main(String[] args) {
                        Order o = Order.newBuilder().setTotal(Money.newBuilder().setCents(5)).build();
                        System.out.println(o.getTotal().getCents() + " " + o.hasAt());
                    }
                }
                """);
        try {
            BuildPlanResult lock = LockPlans.lockBuildPlan(
                            project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                    .run();
            assertThat(lock.errors()).isEmpty();
            assertThat(LockfileReader.read(project.resolve("jk-lock.toml")).schemas())
                    .extracting(SchemaEntry::key)
                    .containsExactly("money@2", "orders-value@5");

            assertThat(build(project, cache).errors()).isEmpty();
            assertThat(anyFile(project.resolve("target"), "Order.class")).isTrue();
            assertThat(anyFile(project.resolve("target"), "Money.class")).isTrue();
            assertThat(anyFile(project.resolve("target"), "Main.class")).isTrue();
        } finally {
            registry.stop(0);
        }

        PathUtil.deleteRecursivelyOrThrow(project.resolve("target"));
        assertThat(build(project, cache).errors()).isEmpty();
        assertThat(anyFile(project.resolve("target"), "Main.class")).isTrue();
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        BuildPlanner.Inputs in = TestInputs.of(project, cache).withSkipTests(true);
        return BuildPlanner.fullPlan(in).run();
    }

    private static String version(String subject, int version, long id, String schema, String reference) {
        return "{\"subject\":" + MiniJson.write(subject) + ",\"version\":" + version + ",\"id\":" + id
                + ",\"schemaType\":\"PROTOBUF\",\"schema\":" + MiniJson.write(schema) + ",\"references\":["
                + reference + "]}";
    }
}
