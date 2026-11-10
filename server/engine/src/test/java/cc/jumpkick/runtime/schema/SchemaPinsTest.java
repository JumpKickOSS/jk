// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.SchemaEntry;
import cc.jumpkick.model.JkBuild;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaPinsTest {

    private static final String MONEY =
            "{\"type\":\"record\",\"name\":\"Money\",\"namespace\":\"acme\",\"fields\":[{\"name\":\"cents\",\"type\":\"long\"}]}";
    private static final String ORDER =
            "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"acme\",\"fields\":[{\"name\":\"total\",\"type\":\"acme.Money\"}]}";

    @TempDir
    Path tmp;

    private @Nullable HttpServer registry;
    private final Map<String, String> served = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private String url = "";
    private Map<String, String> shell = Map.of();

    @BeforeEach
    void setUp() throws IOException {
        served.put("/subjects/money/versions/1", version("money", 1, 7, MONEY, ""));
        served.put(
                "/subjects/orders-value/versions/latest",
                version(
                        "orders-value",
                        3,
                        101,
                        ORDER,
                        "{\"name\":\"acme.Money\",\"subject\":\"money\",\"version\":1}"));
        served.put("/subjects/orders-value/versions/3", served.get("/subjects/orders-value/versions/latest"));
        String expected = "Basic " + Base64.getEncoder().encodeToString("key:secret".getBytes(StandardCharsets.UTF_8));
        HttpServer registry = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.registry = registry;
        registry.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(path);
            }
            String body = served.get(path);
            int status = !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    ? 401
                    : body == null ? 404 : 200;
            byte[] bytes = body != null && status == 200 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        registry.start();
        int port = registry.getAddress().getPort();
        url = "http://127.0.0.1:" + port;
        shell = Map.of(
                "JK_REPO_CONFLUENT_HOST", "127.0.0.1:" + port,
                "JK_REPO_CONFLUENT_USERNAME", "key",
                "JK_REPO_CONFLUENT_PASSWORD", "secret");
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.stop(0);
    }

    @Test
    void jk_lock_pins_each_subject_and_what_it_references_with_the_registry_credential() throws Exception {
        Cas cas = new Cas(tmp.resolve("store"));

        Lockfile lock = pin(null, cas);

        assertThat(lock.schemas())
                .extracting(SchemaEntry::key, SchemaEntry::id, SchemaEntry::type, SchemaEntry::references)
                .containsExactly(
                        tuple("orders-value@3", 101L, "avro", List.of(new SchemaEntry.Ref("acme.Money", "money", 1))),
                        tuple("money@1", 7L, "avro", List.of()));
        assertThat(lock.schemas().getFirst().sha256Hex())
                .isEqualTo(Hashing.sha256Hex(ORDER.getBytes(StandardCharsets.UTF_8)));
        assertThat(LockfileReader.parse(LockfileWriter.render(lock)).schemas())
                .containsExactlyInAnyOrderElementsOf(lock.schemas());
    }

    @Test
    void a_lock_that_already_answers_the_table_keeps_its_rows_without_asking_the_registry() throws Exception {
        Cas cas = new Cas(tmp.resolve("store"));
        Lockfile first = pin(null, cas);
        requests.clear();

        Lockfile again = pin(first, cas);

        assertThat(again.schemas()).isEqualTo(first.schemas());
        assertThat(requests).isEmpty();
    }

    /** Modules laying the same schema set at once each get the whole set; none fails or reads half. */
    @Test
    void concurrent_layers_of_one_set_all_get_it_whole() throws Exception {
        Cas cas = new Cas(tmp.resolve("store"));
        Path project = project();
        Path workspaceLock = tmp.resolve("jk-lock.toml");
        Lockfile lock = RequestShell.where(
                shell,
                () -> SchemaPins.pin(Lockfile.empty("0.0.0"), null, tmp, Map.of(project, build()), cas, label -> {}));
        LockfileWriter.write(lock, workspaceLock);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<SchemaFiles.Dir>> laid = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                laid.add(pool.submit(() -> SchemaFiles.forPlugin("avro", build(), project, workspaceLock, cas)
                        .orElseThrow()));
            }
            for (var f : laid) {
                SchemaFiles.Dir dir = f.get();
                assertThat(Files.readString(dir.dir().resolve("orders-value.avsc")))
                        .isEqualTo(ORDER);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void the_generator_reads_a_member_s_pinned_schemas_from_the_store_with_the_registry_gone() throws Exception {
        Cas cas = new Cas(tmp.resolve("store"));
        Path project = project();
        Path workspaceLock = tmp.resolve("jk-lock.toml");
        Lockfile lock = RequestShell.where(
                shell,
                () -> SchemaPins.pin(Lockfile.empty("0.0.0"), null, tmp, Map.of(project, build()), cas, label -> {}));
        assertThat(lock.schemas()).allSatisfy(row -> assertThat(row.module()).isEqualTo("orders"));
        LockfileWriter.write(lock, workspaceLock);
        if (registry != null) registry.stop(0);

        SchemaFiles.Dir dir = SchemaFiles.forPlugin("avro", build(), project, workspaceLock, cas)
                .orElseThrow();

        assertThat(Files.readString(dir.dir().resolve("orders-value.avsc"))).isEqualTo(ORDER);
        assertThat(Files.readString(dir.dir().resolve("money.avsc"))).isEqualTo(MONEY);
        assertThat(SchemaFiles.forPlugin("protobuf", build(), project, workspaceLock, cas))
                .isEmpty();
    }

    @Test
    void a_schema_the_registry_changed_at_the_same_version_is_refused_naming_the_subject() throws Exception {
        Cas pinning = new Cas(tmp.resolve("pinning-store"));
        Path project = project();
        LockfileWriter.write(pin(null, pinning), project.resolve("jk-lock.toml"));
        served.put("/subjects/money/versions/1", version("money", 1, 7, MONEY.replace("cents", "units"), ""));

        assertThatThrownBy(() -> RequestShell.where(
                        shell,
                        () -> SchemaFiles.forPlugin(
                                "avro",
                                build(),
                                project,
                                project.resolve("jk-lock.toml"),
                                new Cas(tmp.resolve("fresh")))))
                .hasMessage("money v1: registry changed it since jk lock; run jk lock -F");
    }

    @Test
    void a_protobuf_subject_and_what_it_imports_land_at_their_import_paths_without_well_known_types() throws Exception {
        String money = "syntax = \"proto3\";\npackage acme;\nmessage Money { int64 cents = 1; }\n";
        String order = "syntax = \"proto3\";\npackage acme;\nimport \"common/money.proto\";\n"
                + "import \"google/protobuf/timestamp.proto\";\n"
                + "message Order { Money total = 1; google.protobuf.Timestamp at = 2; }\n";
        served.put("/subjects/money/versions/2", protobuf("money", 2, 8, money, ""));
        served.put(
                "/subjects/orders-value/versions/latest",
                protobuf(
                        "orders-value",
                        5,
                        102,
                        order,
                        "{\"name\":\"common/money.proto\",\"subject\":\"money\",\"version\":2},"
                                + "{\"name\":\"google/protobuf/timestamp.proto\",\"subject\":\"timestamp\",\"version\":1}"));
        Cas cas = new Cas(tmp.resolve("store"));
        Path project = project();
        Lockfile lock = RequestShell.where(
                shell,
                () -> SchemaPins.pin(
                        Lockfile.empty("0.0.0"), null, project, Map.of(project, build()), cas, label -> {}));
        LockfileWriter.write(lock, project.resolve("jk-lock.toml"));

        assertThat(lock.schemas()).extracting(SchemaEntry::key).containsExactly("orders-value@5", "money@2");
        assertThat(lock.schemas().getFirst().references())
                .containsExactly(new SchemaEntry.Ref("common/money.proto", "money", 2));
        SchemaFiles.Dir dir = SchemaFiles.forPlugin("protobuf", build(), project, project.resolve("jk-lock.toml"), cas)
                .orElseThrow();
        assertThat(Files.readString(dir.dir().resolve("orders-value.proto"))).isEqualTo(order);
        assertThat(Files.readString(dir.dir().resolve("common/money.proto"))).isEqualTo(money);
        assertThat(dir.dir().resolve("google")).doesNotExist();
        assertThat(SchemaFiles.forPlugin("avro", build(), project, project.resolve("jk-lock.toml"), cas))
                .isEmpty();
    }

    private static String protobuf(String subject, int version, long id, String schema, String references) {
        return "{\"subject\":" + MiniJson.write(subject) + ",\"version\":" + version + ",\"id\":" + id
                + ",\"schemaType\":\"PROTOBUF\",\"schema\":" + MiniJson.write(schema) + ",\"references\":["
                + references + "]}";
    }

    private Lockfile pin(@Nullable Lockfile previous, Cas cas) throws Exception {
        Path project = project();
        return RequestShell.where(
                shell,
                () -> SchemaPins.pin(
                        Lockfile.empty("0.0.0"), previous, project, Map.of(project, build()), cas, label -> {}));
    }

    private Path project() throws IOException {
        Path project = tmp.resolve("orders");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), manifest());
        return project;
    }

    private JkBuild build() {
        return JkBuildParser.parseLocal(manifest(), tmp.resolve("orders/jk.toml"));
    }

    private String manifest() {
        return """
                name = "orders"
                version = "1.0.0"

                [schema-registry]
                url = "%s"
                subjects = { "orders-value" = "latest" }
                repository = "confluent"
                """.formatted(url);
    }

    private static String version(String subject, int version, long id, String schema, String reference) {
        return "{\"subject\":" + MiniJson.write(subject) + ",\"version\":" + version + ",\"id\":" + id + ",\"schema\":"
                + MiniJson.write(schema) + ",\"references\":[" + reference + "]}";
    }
}
