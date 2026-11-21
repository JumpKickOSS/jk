// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.SchemaPresetExamplesTest.anyFile;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.build;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.example;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.lock;
import static cc.jumpkick.runtime.SchemaPresetExamplesTest.step;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.testing.TestCaches;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The jOOQ example generating from a live database rather than its migrations: an H2 database
 * rebuilt from a script on every connection stands in for a server, and the script sits outside
 * the module, so the schema reaches the step only through its key probe. With no {@code sql} file
 * in the tree the step still generates, an unchanged schema is a hit, and a schema change
 * regenerates. The password is a secret: it reaches the probe and the generator, and nothing the
 * build writes holds it.
 *
 * <p>Network test (Maven Central for the generator); the CAS persists under build/ so repeat runs
 * are warm.
 */
@Tag("integration")
class JooqLiveSchemaTest {

    @Test
    void a_live_schema_generates_without_scripts_hits_unchanged_and_regenerates_on_a_change(@TempDir Path tmp)
            throws Exception {
        Path project = example(tmp, PluginJar.JOOQ, "plugins/jooq");
        Path migrations = project.resolve("src/main/resources/db/migration");
        Path schema = Files.createDirectories(tmp.resolve("live")).resolve("schema.sql");
        Files.writeString(
                schema,
                Files.readString(migrations.resolve("V1__customers.sql"))
                        + Files.readString(migrations.resolve("V2__orders.sql")));
        PathUtil.deleteRecursivelyOrThrow(project.resolve("src/main/resources"));
        String url =
                "jdbc:h2:mem:shop;INIT=RUNSCRIPT FROM '" + schema.toString().replace('\\', '/') + "'";
        Path manifest = project.resolve("jk.toml");
        Files.writeString(
                manifest,
                Files.readString(manifest)
                        .replace("[jooq]\n", "[jooq]\njdbc-url = \"" + url.replace("\\", "\\\\") + "\"\n"));
        Path cache = TestCaches.dir("jooq-live-schema-cache");
        lock(project, cache);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(step(first, "generate-jooq").status())
                .as("no sql file names the schema; the probe does")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(anyFile(project.resolve("target"), "Orders.class")).isTrue();

        assertThat(step(build(project, cache), "generate-jooq").status())
                .as("the same schema")
                .isEqualTo(TaskStatus.SKIPPED);

        Files.writeString(
                schema,
                Files.readString(schema)
                        + "create table shipments (id bigint primary key, order_id bigint not null);\n");
        BuildPlanResult changed = build(project, cache);
        assertThat(changed.errors()).isEmpty();
        assertThat(step(changed, "generate-jooq").status())
                .as("a table added to the database")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(anyFile(project.resolve("target"), "Shipments.class")).isTrue();
    }

    /**
     * The database's init script refuses a connection whose process lacks the password in its
     * environment, so the build passes only when the probe and the generator both received it from
     * {@code env:NAME}; a new password keys nothing, and no file under {@code target/} holds one.
     */
    @Test
    void the_password_reaches_the_probe_and_the_generator_and_no_file_holds_it(@TempDir Path tmp) throws Exception {
        Path project = example(tmp, PluginJar.JOOQ, "plugins/jooq");
        Path migrations = project.resolve("src/main/resources/db/migration");
        Path schema = Files.createDirectories(tmp.resolve("live")).resolve("schema.sql");
        Files.writeString(
                schema,
                """
                CREATE ALIAS IF NOT EXISTS PASSWORD_SEEN AS $$
                String seen() {
                    String pw = System.getenv("JK_JOOQ_JDBC_PASSWORD");
                    if (pw == null || !pw.startsWith("pw-")) throw new IllegalStateException("no password");
                    return "seen";
                }
                $$;
                CALL PASSWORD_SEEN();
                """
                        + Files.readString(migrations.resolve("V1__customers.sql"))
                        + Files.readString(migrations.resolve("V2__orders.sql")));
        PathUtil.deleteRecursivelyOrThrow(project.resolve("src/main/resources"));
        String url =
                "jdbc:h2:mem:shop;INIT=RUNSCRIPT FROM '" + schema.toString().replace('\\', '/') + "'";
        Path manifest = project.resolve("jk.toml");
        Files.writeString(
                manifest,
                Files.readString(manifest)
                        .replace(
                                "excludes = \"flyway_schema_history\"",
                                "excludes = \"flyway_schema_history|PASSWORD_SEEN\"")
                        .replace(
                                "[jooq]\n",
                                "[jooq]\njdbc-url = \"" + url.replace("\\", "\\\\") + "\"\njdbc-user = \"sa\"\n"
                                        + "jdbc-password = \"env:SHOP_DB_PASSWORD\"\n"));
        Files.writeString(project.resolve(".env"), "SHOP_DB_PASSWORD=pw-first-7c41\n");
        Path cache = TestCaches.dir("jooq-live-schema-cache");
        lock(project, cache);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(step(first, "generate-jooq").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(anyFile(project.resolve("target"), "Orders.class")).isTrue();
        assertThat(filesHolding(project.resolve("target"), "pw-first-7c41")).isEmpty();

        Files.writeString(project.resolve(".env"), "SHOP_DB_PASSWORD=pw-second-0d93\n");
        assertThat(step(build(project, cache), "generate-jooq").status())
                .as("a new password is no new input")
                .isEqualTo(TaskStatus.SKIPPED);
    }

    /** The files under {@code dir} whose bytes hold {@code text}. */
    private static List<Path> filesHolding(Path dir, String text) throws Exception {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        List<Path> out = new ArrayList<>();
        PathUtil.forEachRegularFile(dir, (file, attrs) -> {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i + needle.length <= bytes.length; i++) {
                if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
                    out.add(file);
                    return;
                }
            }
        });
        return out;
    }
}
