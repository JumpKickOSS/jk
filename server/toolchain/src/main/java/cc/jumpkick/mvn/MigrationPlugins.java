// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import org.apache.maven.model.Model;

/**
 * The database migration plugins, {@code flyway-maven-plugin} and {@code liquibase-maven-plugin},
 * are rows pointing at the tool recipe: a migration is a command against a database, not a build
 * step, so jk runs each tool through {@code jk tool install} ({@code docs/user/database.md}).
 */
final class MigrationPlugins {

    static final String FLYWAY = "flyway-maven-plugin";
    static final String LIQUIBASE = "liquibase-maven-plugin";

    private MigrationPlugins() {}

    static void report(Model model, ImportReport.Builder report) {
        if (PluginFacts.plugin(model, FLYWAY).isPresent()) {
            report.warning(FLYWAY + ": not imported; install the Flyway CLI with jk tool install"
                    + " (docs/user/database.md)");
        }
        if (PluginFacts.plugin(model, LIQUIBASE).isPresent()) {
            report.warning(LIQUIBASE + ": not imported; install the Liquibase CLI with jk tool install"
                    + " (docs/user/database.md)");
        }
    }
}
