// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual's database-migration recipe ({@code docs/user/database.md}) as a command: Flyway's
 * command line and Liquibase's, each fetched by coordinate with the H2 driver beside it, migrate a
 * file database — the migration lands, so the schema history file exists and the verb exits zero.
 * The URL travels on the command line here; the recipe puts it in {@code .env}.
 *
 * <p>Network test (Maven Central resolves the two tools' closures).
 */
@Tag("network")
class DatabaseMigrationRecipeTest {

    private static final String FLYWAY = "org.flywaydb:flyway-commandline:12.9.0";
    private static final String FLYWAY_MAIN = "org.flywaydb.commandline.Main";
    private static final String LIQUIBASE = "org.liquibase:liquibase-core:5.0.4";
    private static final String LIQUIBASE_MAIN = "liquibase.integration.commandline.LiquibaseCommandLine";
    private static final String H2 = "com.h2database:h2:2.5.250";
    private static final String PICOCLI = "info.picocli:picocli:4.7.7";

    @Test
    void flyway_migrates_an_h2_database_from_the_migrations_directory(@TempDir Path tmp) throws Exception {
        Path migrations = Files.createDirectories(tmp.resolve("src/main/resources/db/migration"));
        Files.writeString(
                migrations.resolve("V1__customers.sql"),
                "create table customers (id bigint primary key, email varchar(255) not null);\n");
        Path db = tmp.resolve("data/dev");

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tmp.resolve("home/cache").toString(),
                "--state-dir",
                tmp.resolve("home").toString(),
                "--main",
                FLYWAY_MAIN,
                "--with",
                H2,
                FLYWAY,
                "--",
                "-url=jdbc:h2:file:" + db,
                "-locations=filesystem:" + migrations,
                "migrate");

        assertThat(exit).isZero();
        assertThat(tmp.resolve("data/dev.mv.db"))
                .as("the H2 file the migration created")
                .isRegularFile();
    }

    @Test
    void liquibase_updates_an_h2_database_from_the_change_log(@TempDir Path tmp) throws Exception {
        Path changelogs = Files.createDirectories(tmp.resolve("src/main/resources/db/changelog"));
        Files.writeString(changelogs.resolve("db.changelog-master.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                  <changeSet id="1" author="jk">
                    <createTable tableName="customers">
                      <column name="id" type="bigint"><constraints primaryKey="true"/></column>
                      <column name="email" type="varchar(255)"><constraints nullable="false"/></column>
                    </createTable>
                  </changeSet>
                </databaseChangeLog>
                """);
        Path db = tmp.resolve("data/dev");

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tmp.resolve("home/cache").toString(),
                "--state-dir",
                tmp.resolve("home").toString(),
                "--main",
                LIQUIBASE_MAIN,
                "--with",
                H2,
                "--with",
                PICOCLI,
                LIQUIBASE,
                "--",
                "--url=jdbc:h2:file:" + db,
                "--changelog-file=db/changelog/db.changelog-master.xml",
                "--search-path=" + tmp.resolve("src/main/resources"),
                "update");

        assertThat(exit).isZero();
        assertThat(tmp.resolve("data/dev.mv.db"))
                .as("the H2 file the update created")
                .isRegularFile();
    }
}
