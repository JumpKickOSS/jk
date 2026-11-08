// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LockSchemasTest {

    private static final String REGISTRY = "https://registry.example.com";

    private static final SchemaEntry ORDER = new SchemaEntry(
            null,
            REGISTRY,
            "orders-value",
            3,
            101,
            "AVRO",
            "sha256:" + "a".repeat(64),
            List.of(new SchemaEntry.Ref("acme.Money", "money", 1)));
    private static final SchemaEntry MONEY =
            new SchemaEntry(null, REGISTRY, "money", 1, 7, "avro", "sha256:" + "b".repeat(64), List.of());

    @Test
    void schema_rows_survive_a_write_and_a_read() {
        Lockfile lock = Lockfile.empty("0.0.0").withSchemas(List.of(ORDER, MONEY));

        String text = LockfileWriter.render(lock);

        assertThat(text).contains("[[schema]]\nregistry = \"" + REGISTRY + "\"\nsubject  = \"money\"\nversion  = 1\n");
        assertThat(text).contains("references = [{ name = \"acme.Money\", subject = \"money\", version = 1 }]");
        assertThat(LockfileReader.parse(text).schemas()).containsExactly(MONEY, ORDER);
    }

    @Test
    void the_closure_follows_references_and_latest_takes_the_pinned_version() {
        LockSchemas.Declared declared =
                new LockSchemas.Declared(null, REGISTRY + "/", Map.of("orders-value", "latest"), null);

        assertThat(LockSchemas.closure(List.of(MONEY, ORDER), declared)).containsExactly(ORDER, MONEY);
    }

    @Test
    void latest_takes_the_newest_pinned_version_of_the_subject() {
        SchemaEntry moneyTwo =
                new SchemaEntry(null, REGISTRY, "money", 2, 9, "avro", "sha256:" + "c".repeat(64), List.of());
        LockSchemas.Declared declared = new LockSchemas.Declared(null, REGISTRY, Map.of("money", "latest"), null);

        assertThat(LockSchemas.closure(List.of(MONEY, moneyTwo), declared)).containsExactly(moneyTwo);
    }

    @Test
    void two_versions_of_one_subject_in_a_module_name_the_subject_and_both() {
        SchemaEntry moneyTwo =
                new SchemaEntry(null, REGISTRY, "money", 2, 9, "avro", "sha256:" + "c".repeat(64), List.of());
        LockSchemas.Declared declared =
                new LockSchemas.Declared(null, REGISTRY, Map.of("orders-value", "3", "money", "latest"), null);

        assertThatThrownBy(() -> LockSchemas.closure(List.of(MONEY, moneyTwo, ORDER), declared))
                .hasMessage("money: v1 and v2 both reach one module; keep one");
    }

    @Test
    void a_declared_version_the_lock_does_not_pin_names_the_subject() {
        LockSchemas.Declared declared = new LockSchemas.Declared(null, REGISTRY, Map.of("orders-value", "4"), null);

        assertThatThrownBy(() -> LockSchemas.closure(List.of(MONEY, ORDER), declared))
                .hasMessage("orders-value: not in jk-lock.toml; run jk lock");
    }

    @Test
    void a_reference_the_lock_lacks_names_it() {
        LockSchemas.Declared declared = new LockSchemas.Declared(null, REGISTRY, Map.of("orders-value", "3"), null);

        assertThatThrownBy(() -> LockSchemas.closure(List.of(ORDER), declared))
                .hasMessage("money@1: not in jk-lock.toml; run jk lock");
    }

    @Test
    void a_module_table_reads_into_its_declared_subjects() {
        JkBuild build = JkBuildParser.parseLocal("""
                name = "orders"
                version = "1.0.0"

                [schema-registry]
                url = "https://registry.example.com"
                subjects = { "orders-value" = "3" }
                repository = "confluent"
                """, Path.of("/work/orders/jk.toml"));

        assertThat(LockSchemas.of("orders", build))
                .contains(new LockSchemas.Declared("orders", REGISTRY, Map.of("orders-value", "3"), "confluent"));
    }
}
