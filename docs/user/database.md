# Database migrations

Flyway and Liquibase migrate a schema. Under Spring Boot, Quarkus and Micronaut the migration runs
inside the application at startup — `flyway-core` or `liquibase-core` on the classpath and the
framework's own properties — and the build has nothing to do. Outside the application — a
migration against a shared development database, an `info`, a `repair`, a `rollback`, a
`validate` in CI — the tool is a **command against a database**, not a build step: jk runs it as
a [tool](tools.md), pinned by coordinate, with the JDBC driver beside it and the database URL
from the environment.

One shape for both tools:

1. **Install once**, pinned, with the driver: `jk tool install <tool coordinate> --with <driver>`.
2. **Point it at the database through the environment**: the tool's own variables in the
   project's `.env` (`FLYWAY_URL`, `LIQUIBASE_COMMAND_URL`), which every process jk spawns in the
   project inherits — and the shell always wins over the file, so CI sets the real URL.
3. **Run the verb**: `flyway migrate`, `liquibase update`.

Nothing lands in `jk.toml`: the migrations are resources of the module (`src/main/resources/db/migration`,
`src/main/resources/db/changelog`), the application still runs them at startup, and the
[`[jooq]` preset](generate.md#jooq--jooq-classes-from-the-migrations) generates from the same
scripts without a database at all.

## Flyway

```bash
jk tool install org.flywaydb:flyway-commandline:12.9.0 \
  --with org.postgresql:postgresql:42.7.9            # the driver your URL needs
```

```dotenv
# .env — read by every process jk starts in this project; the shell overrides it
FLYWAY_URL=jdbc:postgresql://localhost:5432/app
FLYWAY_USER=app
FLYWAY_PASSWORD=app
FLYWAY_LOCATIONS=filesystem:src/main/resources/db/migration
```

```bash
flyway migrate      # or: flyway info | validate | repair | clean
```

Every Flyway setting is an `FLYWAY_<SETTING>` variable or a `-setting=value` argument, so a
one-off run needs no file: `flyway -url=jdbc:h2:file:./target/dev -locations=filesystem:src/main/resources/db/migration migrate`.
H2, HSQLDB, Derby and SQLite drivers work with `flyway-core` alone; PostgreSQL, MySQL, SQL Server,
Oracle and the rest ship as `flyway-database-*` modules the command line already carries — only
the JDBC driver is yours to add with `--with`.

## Liquibase

```bash
jk tool install org.liquibase:liquibase-core:5.0.4 \
  --main liquibase.integration.commandline.LiquibaseCommandLine \
  --with org.postgresql:postgresql:42.7.9 \
  --with info.picocli:picocli:4.7.7
```

`liquibase-core`'s manifest names a launcher that wants a `LIQUIBASE_HOME` distribution, so the
install names the command-line class itself; picocli, which that class parses arguments with, is
optional in Liquibase's POM and so rides along explicitly.

```dotenv
LIQUIBASE_COMMAND_URL=jdbc:postgresql://localhost:5432/app
LIQUIBASE_COMMAND_USERNAME=app
LIQUIBASE_COMMAND_PASSWORD=app
LIQUIBASE_COMMAND_CHANGELOG_FILE=db/changelog/db.changelog-master.yaml
LIQUIBASE_SEARCH_PATH=src/main/resources
```

```bash
liquibase update    # or: liquibase status | rollback-count --count=1 | validate
```

## In a test

The recipe against a database a test owns — a Testcontainer's JDBC URL, an H2 file — is the same
command with the URL on the command line: `jk tool run org.flywaydb:flyway-commandline:12.9.0
--with com.h2database:h2:2.5.250 -- -url=jdbc:h2:file:./target/it -locations=filesystem:src/main/resources/db/migration migrate`.
The tool's closure is resolved once and cached; a second run costs the JVM start.

## From Maven

`flyway-maven-plugin` and `liquibase-maven-plugin` have no `jk.toml` table: `jk import` writes a
row naming this recipe with the POM's URL and change log
([Migration](migration.md#which-maven-plugins-import-and-how-well)).

## Related

[Tools](tools.md) · [Generate — `[jooq]`](generate.md#jooq--jooq-classes-from-the-migrations) · [Install — `.env`](install.md#jk-env)
