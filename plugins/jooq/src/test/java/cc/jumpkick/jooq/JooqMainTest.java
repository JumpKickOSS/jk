// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main over the real jOOQ generator (on this test's classpath): two Flyway-named migrations
 * are applied in version order through DDLDatabase and the resulting tables generate their
 * classes into the package, the options reach the configuration, and a broken script is the
 * generator's own failure.
 */
class JooqMainTest {

    private static final String V1 = """
            create table customers (
              id bigint primary key,
              email varchar(255) not null
            );
            """;

    private static final String V2 = """
            create table orders (
              id bigint primary key,
              customer_id bigint not null references customers(id),
              total decimal(10, 2) not null
            );
            """;

    @Test
    void migrations_generate_the_tables_in_version_order(@TempDir Path tmp) throws Exception {
        Path migrations = tmp.resolve("src/main/resources/db/migration");
        Path orders = write(migrations.resolve("V2__orders.sql"), V2);
        Path customers = write(migrations.resolve("V1__customers.sql"), V1);
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = JooqMain.run(new String[] {
            "--out", out.toString(), "--package", "com.acme.jooq", "--pojos", orders.toString(), customers.toString()
        });

        assertThat(exit).isZero();
        Path pkg = out.resolve("com/acme/jooq");
        assertThat(pkg.resolve("tables/Customers.java")).content().contains("package com.acme.jooq.tables;");
        assertThat(pkg.resolve("tables/Orders.java")).content().contains("CUSTOMER_ID");
        assertThat(pkg.resolve("tables/records/OrdersRecord.java")).isRegularFile();
        assertThat(pkg.resolve("tables/pojos/Orders.java")).isRegularFile();
        assertThat(pkg.resolve("DefaultSchema.java"))
                .as("the output schema is the default")
                .isRegularFile();
    }

    @Test
    void the_options_shape_the_configuration(@TempDir Path tmp) {
        JooqMain.Options options = JooqMain.parse(new String[] {
            "--out",
            tmp.resolve("out").toString(),
            "--package",
            "com.acme.db",
            "--schema",
            "shop",
            "--name-case",
            "lower",
            "--includes",
            "orders",
            "--excludes",
            "flyway_.*",
            "--no-records",
            "--daos",
            "--fluent-setters",
            "--property",
            "parseIgnoreComments=true",
            tmp.resolve("V1__a.sql").toString()
        });

        String xml = JooqMain.configuration(options, tmp.resolve("staged"));

        assertThat(xml)
                .contains("<name>org.jooq.meta.extensions.ddl.DDLDatabase</name>")
                .contains("<key>scripts</key><value>" + tmp.resolve("staged") + "</value>")
                .contains("<key>sort</key><value>semantic</value>")
                .contains("<key>defaultNameCase</key><value>lower</value>")
                .contains("<key>parseIgnoreComments</key><value>true</value>")
                .contains("<inputSchema>shop</inputSchema>")
                .contains("<outputSchemaToDefault>true</outputSchemaToDefault>")
                .contains("<includes>orders</includes>")
                .contains("<excludes>flyway_.*</excludes>")
                .contains("<records>false</records>")
                .contains("<pojos>true</pojos>")
                .contains("<daos>true</daos>")
                .contains("<fluentSetters>true</fluentSetters>")
                .contains("<packageName>com.acme.db</packageName>")
                .doesNotContain("<jdbc>");
    }

    @Test
    void a_live_database_is_a_jdbc_block_and_no_ddl_database(@TempDir Path tmp) {
        JooqMain.Options options = JooqMain.parse(new String[] {
            "--out",
            tmp.toString(),
            "--package",
            "com.acme.db",
            "--jdbc-url",
            "jdbc:postgresql://localhost/shop",
            "--jdbc-user",
            "shop",
            "--jdbc-password",
            "s&cret",
            tmp.resolve("V1__a.sql").toString()
        });

        String xml = JooqMain.configuration(options, null);

        assertThat(xml)
                .contains("<jdbc>")
                .contains("<url>jdbc:postgresql://localhost/shop</url>")
                .contains("<user>shop</user>")
                .contains("<password>s&amp;cret</password>")
                .doesNotContain("DDLDatabase")
                .doesNotContain("<properties>");
    }

    @Test
    void a_broken_script_is_the_generators_own_failure(@TempDir Path tmp) throws Exception {
        Path broken = write(tmp.resolve("V1__broken.sql"), "create tabel nope (id int);");
        Path out = Files.createDirectories(tmp.resolve("out"));

        assertThatThrownBy(() -> JooqMain.run(
                        new String[] {"--out", out.toString(), "--package", "com.acme.jooq", broken.toString()}))
                .hasMessageContaining("Error while exporting schema")
                .hasStackTraceContaining("tabel");
    }

    @Test
    void two_scripts_with_one_name_are_refused(@TempDir Path tmp) throws Exception {
        Path a = write(tmp.resolve("a/V1__init.sql"), V1);
        Path b = write(tmp.resolve("b/V1__init.sql"), V2);

        assertThatThrownBy(() -> JooqMain.stageScripts(List.of(a, b))).hasMessageContaining("V1__init.sql");
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
