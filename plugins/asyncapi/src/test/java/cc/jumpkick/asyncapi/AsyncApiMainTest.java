// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.asyncapi;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AsyncAPI documents as the JSON Schemas of their payloads, and the classes jsonschema2pojo makes of them. */
class AsyncApiMainTest {

    private static final String V3 = """
            asyncapi: 3.0.0
            info: { title: Orders, version: 1.0.0 }
            channels:
              orders:
                address: orders
                messages:
                  orderCreated:
                    $ref: '#/components/messages/OrderCreated'
                  orderCancelled:
                    name: order-cancelled
                    payload:
                      type: object
                      properties:
                        id: { type: integer }
                        reason: { type: string }
            components:
              messages:
                OrderCreated:
                  payload:
                    $ref: '#/components/schemas/Order'
              schemas:
                Order:
                  type: object
                  properties:
                    id: { type: integer }
                    total: { $ref: '#/components/schemas/Money' }
                Money:
                  type: object
                  properties:
                    cents: { type: integer }
            """;

    private static final String V2 = """
            asyncapi: 2.6.0
            info: { title: Users, version: 1.0.0 }
            channels:
              user/signedup:
                subscribe:
                  operationId: userSignedUp
                  message:
                    payload:
                      type: object
                      properties:
                        email: { type: string, format: email }
              user/avro:
                publish:
                  message:
                    schemaFormat: application/vnd.apache.avro;version=1.9.0
                    payload: { type: record, name: Avro, fields: [] }
            """;

    @Test
    void a_v3_document_s_schemas_and_inline_payloads_become_schema_files_with_refs_to_each_other(@TempDir Path tmp)
            throws Exception {
        Path doc = tmp.resolve("asyncapi.yaml");
        Files.writeString(doc, V3);

        AsyncApiMain.extract(doc, tmp.resolve("out"));

        assertThat(tmp.resolve("out"))
                .isDirectoryContaining("glob:**/Order.json")
                .isDirectoryContaining("glob:**/Money.json")
                .isDirectoryContaining("glob:**/OrderCancelled.json")
                .isDirectoryNotContaining("glob:**/OrderCreated.json");
        assertThat(Files.readString(tmp.resolve("out/Order.json"))).contains("\"$ref\":\"Money.json\"");
    }

    @Test
    void a_v2_document_s_operation_payloads_are_named_by_operation_and_other_formats_skipped(@TempDir Path tmp)
            throws Exception {
        Path doc = tmp.resolve("asyncapi.yaml");
        Files.writeString(doc, V2);

        assertThat(AsyncApiMain.extract(doc, tmp.resolve("out"))).containsExactly(tmp.resolve("out/UserSignedUp.json"));
    }

    @Test
    void jsonschema2pojo_generates_the_payload_classes(@TempDir Path tmp) throws Exception {
        Path doc = tmp.resolve("asyncapi.yaml");
        Files.writeString(doc, V3);
        Path out = tmp.resolve("gen");

        AsyncApiMain.main(new String[] {"-t", out.toString(), "-p", "com.acme.orders", "-D", "--", doc.toString()});

        assertThat(out.resolve("com/acme/orders/Order.java")).exists();
        assertThat(out.resolve("com/acme/orders/Money.java")).exists();
        assertThat(out.resolve("com/acme/orders/OrderCancelled.java")).exists();
        assertThat(Files.readString(out.resolve("com/acme/orders/Order.java"))).contains("private Money total;");
    }

    /** A run leaves no scratch in the temp directory: the step runs again and again in a warm worker. */
    @Test
    void a_run_leaves_no_scratch_behind(@TempDir Path tmp) throws Exception {
        Path doc = tmp.resolve("asyncapi.yaml");
        Files.writeString(doc, V3);
        Set<String> before = scratch();

        AsyncApiMain.main(
                new String[] {"-t", tmp.resolve("gen").toString(), "-p", "com.acme.orders", "-D", "--", doc.toString()
                });

        assertThat(scratch()).isSubsetOf(before);
    }

    private static Set<String> scratch() throws Exception {
        Set<String> names = new TreeSet<>();
        PathUtil.forEachChild(Path.of(System.getProperty("java.io.tmpdir")), (p, attrs) -> {
            String name = p.getFileName().toString();
            if (name.startsWith("jk-asyncapi-")) names.add(name);
            return true;
        });
        return names;
    }

    /** An Avro payload compiles with the Avro compiler when the table sets avro. */
    @Test
    void an_avro_payload_compiles_beside_the_json_ones(@TempDir Path tmp) throws Exception {
        Path doc = tmp.resolve("asyncapi.yaml");
        Files.writeString(
                doc,
                V2.replace(
                        "payload: { type: record, name: Avro, fields: [] }",
                        "payload: { type: record, name: UserAvro, namespace: com.acme.avro, fields: [{ name: id, type: long }] }"));
        Path out = tmp.resolve("gen");

        AsyncApiMain.main(
                new String[] {"-t", out.toString(), "-p", "com.acme.users", AsyncApiMain.AVRO, "--", doc.toString()});

        assertThat(out.resolve("com/acme/users/UserSignedUp.java")).exists();
        assertThat(out.resolve("com/acme/avro/UserAvro.java")).exists();
    }

    /** A Protobuf payload's text becomes a .proto file named after its message. */
    @Test
    void a_protobuf_payload_becomes_a_proto_file(@TempDir Path tmp) throws Exception {
        Path doc = tmp.resolve("asyncapi.yaml");
        Files.writeString(doc, """
                asyncapi: 3.0.0
                info: { title: Shipping, version: 1.0.0 }
                channels:
                  shipping:
                    messages:
                      orderShipped:
                        payload:
                          schemaFormat: application/vnd.google.protobuf;version=3
                          schema: |
                            syntax = "proto3";
                            package acme;
                            message OrderShipped { int64 id = 1; }
                """);

        List<Path> written = AsyncApiMain.extract(doc, tmp.resolve("json"), null, tmp.resolve("proto"));

        assertThat(written).containsExactly(tmp.resolve("proto/ordershipped.proto"));
        assertThat(Files.readString(written.getFirst())).contains("message OrderShipped");
    }

    @Test
    void the_table_names_its_documents_and_flags() {
        GeneratorEntry entry = AsyncApiPreset.entry(
                new PluginConfig("asyncapi", Map.of("package", "com.acme", "jsr303", true)),
                List.of(Path.of("/shelf/jk-asyncapi.jar")));

        assertThat(entry.stepName()).isEqualTo("generate-asyncapi");
        assertThat(entry.inputs()).containsExactly("src/main/asyncapi/asyncapi.yaml");
        assertThat(entry.args())
                .containsExactly("-t", "${out}", "-a", "JACKSON", "-p", "com.acme", "-303", "-D", "--", "${inputs}");
        assertThat(entry.classpath()).hasSize(2);
        assertThat(AsyncApiPreset.entry(new PluginConfig("asyncapi", Map.of("avro", "1.12.2")), List.of())
                        .args())
                .contains(AsyncApiMain.AVRO);
    }

    @Test
    void names_become_class_names() {
        assertThat(AsyncApiMain.className("order-created")).isEqualTo("OrderCreated");
        assertThat(AsyncApiMain.className("user/signedup")).isEqualTo("UserSignedup");
        assertThat(AsyncApiMain.className("1st")).isEqualTo("_1st");
    }
}
