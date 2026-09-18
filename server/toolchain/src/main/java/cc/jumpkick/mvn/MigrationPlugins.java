// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The database migration plugins, {@code flyway-maven-plugin} and {@code liquibase-maven-plugin},
 * are rows naming the tool recipe: a migration is a command against a database, not a build step,
 * so jk runs each tool through {@code jk tool install} with the JDBC driver beside it and the URL
 * from the environment ({@code docs/user/database.md}). The row carries what the POM knew — the
 * URL, the user, the locations or change log — as the values to put in {@code .env} and on the
 * command line; an unresolved {@code ${…}} is left out.
 */
final class MigrationPlugins {

    static final String FLYWAY = "flyway-maven-plugin";
    static final String LIQUIBASE = "liquibase-maven-plugin";

    private MigrationPlugins() {}

    static void report(Model model, ImportReport.Builder report) {
        Plugin flyway = PluginFacts.plugin(model, FLYWAY).orElse(null);
        if (flyway != null) {
            String url = value(flyway, "url");
            String locations = value(flyway, "locations");
            report.warning("`" + FLYWAY + "` is the Flyway tool recipe, not a build step: `jk tool install"
                    + " org.flywaydb:flyway-commandline:<version> --with <jdbc driver>` once, then `flyway migrate`"
                    + " with `FLYWAY_URL`" + (url == null ? "" : " (`" + url + "`)") + " in `.env`"
                    + (locations == null ? "" : " and `FLYWAY_LOCATIONS=" + locations + "`")
                    + " — see docs/user/database.md; nothing was written.");
        }
        Plugin liquibase = PluginFacts.plugin(model, LIQUIBASE).orElse(null);
        if (liquibase != null) {
            String url = value(liquibase, "url");
            String changeLog = value(liquibase, "changeLogFile");
            report.warning("`" + LIQUIBASE + "` is the Liquibase tool recipe, not a build step: `jk tool install"
                    + " org.liquibase:liquibase-core:<version> --main liquibase.integration.commandline.LiquibaseCommandLine"
                    + " --with <jdbc driver> --with info.picocli:picocli:<version>` once, then `liquibase update` with"
                    + " `LIQUIBASE_COMMAND_URL`" + (url == null ? "" : " (`" + url + "`)") + " in `.env`"
                    + (changeLog == null ? "" : " and `LIQUIBASE_COMMAND_CHANGELOG_FILE=" + changeLog + "`")
                    + " — see docs/user/database.md; nothing was written.");
        }
    }

    /** The usable text of {@code <name>} in the plugin's configurations, or null. */
    private static @Nullable String value(Plugin plugin, String name) {
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            String text = PluginFacts.child(config, name);
            if (text != null) return text;
        }
        return null;
    }
}
