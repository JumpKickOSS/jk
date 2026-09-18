// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code jooq-codegen-maven} is the {@code [jooq]} preset: {@code <generator><target><packageName>}
 * is {@code package}, {@code <database><inputSchema>} / {@code <includes>} / {@code <excludes>} are
 * their keys, the {@code <generate>} switches {@code records}, {@code pojos}, {@code daos} and
 * {@code fluentSetters} are theirs, a {@code <jdbc>} block with a resolvable URL is the {@code
 * jdbc-*} keys, the plugin version is {@code version} — each written only when it differs from the
 * preset's default. A {@code DDLDatabase} configuration's {@code scripts} property is {@code sql}.
 * The target {@code <directory>} is the preset's own contribution, so an {@code add-source} root
 * inside it is not written as an {@code extra-src}; a directory under {@code src/main/java} is a
 * row, since the preset generates into the build, not the tree.
 */
final class JooqPlugin {

    static final String ARTIFACT = "jooq-codegen-maven";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the jooq preset's output; `[jooq]` folds the generated classes into the"
            + " compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_OUTPUT = "target/generated-sources/jooq";
    private static final String DDL_DATABASE = "org.jooq.meta.extensions.ddl.DDLDatabase";

    /** The table (null without the plugin) and the output root the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
    }

    private JooqPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        Map<String, Object> values = new LinkedHashMap<>();
        String output = DEFAULT_OUTPUT;
        boolean live = false;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            Xpp3Dom generator = config.getChild("generator");
            if (generator != null) {
                output = target(generator, baseDir, values, output);
                database(generator.getChild("database"), baseDir, values);
                generate(generator.getChild("generate"), values);
            }
            Xpp3Dom jdbc = config.getChild("jdbc");
            if (jdbc != null) live |= jdbc(jdbc, values, report);
        }
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null && !version.equals("3.21.8")) values.put("version", version);
        if (output.startsWith("src/")) {
            report.warning("`" + ARTIFACT + "` generates into `" + output + "`, a source root; `[jooq]` generates"
                    + " into the build and folds the classes into the compile, so delete the checked-in copies.");
        }
        report.warning("`" + ARTIFACT + "` is `[jooq]`: the classes are generated into the compile from "
                + (live
                        ? "the live database the `jdbc-*` keys name, with the migrations under `sql` as the step's cache key"
                        : "the DDL scripts `sql` names, applied to an in-memory database")
                + "; the module's `org.jooq:jooq` dependency stays and is expected at the generator's version ("
                + (version != null ? version : "3.21.8") + ").");
        return new Mapped(new PluginConfig("jooq", values), Map.of(output, ADD_SOURCE_ROW));
    }

    /** {@code <target>}: the package and the output directory. */
    private static String target(Xpp3Dom generator, @Nullable Path baseDir, Map<String, Object> values, String output) {
        Xpp3Dom target = generator.getChild("target");
        if (target == null) return output;
        String pkg = PluginFacts.child(target, "packageName");
        if (pkg != null) values.put("package", pkg);
        String directory = PluginFacts.child(target, "directory");
        return directory == null ? output : SourceTreePlugins.moduleRelative(directory, baseDir);
    }

    /** {@code <database>}: the schema, the name filters and a DDLDatabase's scripts. */
    private static void database(@Nullable Xpp3Dom database, @Nullable Path baseDir, Map<String, Object> values) {
        if (database == null) return;
        String schema = PluginFacts.child(database, "inputSchema");
        if (schema != null && !schema.equals("PUBLIC")) values.put("schema", schema);
        String includes = PluginFacts.child(database, "includes");
        if (includes != null && !includes.equals(".*")) values.put("includes", includes);
        String excludes = PluginFacts.child(database, "excludes");
        if (excludes != null && !excludes.isEmpty()) values.put("excludes", excludes);
        if (!DDL_DATABASE.equals(PluginFacts.child(database, "name"))) return;
        Xpp3Dom properties = database.getChild("properties");
        if (properties == null) return;
        for (Xpp3Dom property : properties.getChildren("property")) {
            String key = PluginFacts.child(property, "key");
            String value = PluginFacts.child(property, "value");
            if (key == null || value == null) continue;
            switch (key) {
                case "scripts" -> values.put("sql", SourceTreePlugins.moduleRelative(value, baseDir));
                case "defaultNameCase" -> {
                    if (!value.equals("as_is")) values.put("name-case", value);
                }
                case "sort", "unqualifiedSchema" -> {}
                default -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> extra = (Map<String, String>)
                            values.computeIfAbsent("properties", k -> new LinkedHashMap<String, String>());
                    extra.put(key, value);
                }
            }
        }
    }

    /** {@code <generate>}: the switches the preset has keys for. */
    private static void generate(@Nullable Xpp3Dom generate, Map<String, Object> values) {
        if (generate == null) return;
        String records = PluginFacts.child(generate, "records");
        if (records != null && !EnvValues.parseBool(records).orElse(true)) values.put("records", false);
        if (EnvValues.parseBool(PluginFacts.child(generate, "pojos")).orElse(false)) values.put("pojos", true);
        if (EnvValues.parseBool(PluginFacts.child(generate, "daos")).orElse(false)) values.put("daos", true);
        if (EnvValues.parseBool(PluginFacts.child(generate, "fluentSetters")).orElse(false)) {
            values.put("fluent-setters", true);
        }
    }

    /** {@code <jdbc>}: the live database, when its URL is a value and not an unresolved property. */
    private static boolean jdbc(Xpp3Dom jdbc, Map<String, Object> values, ImportReport.Builder report) {
        String url = PluginFacts.child(jdbc, "url");
        if (url == null) {
            report.warning("`" + ARTIFACT + "` reads a live database whose `<jdbc><url>` is not resolvable here;"
                    + " `[jooq]` generates from the DDL scripts under `sql` unless `jdbc-url` names the database.");
            return false;
        }
        values.put("jdbc-url", url);
        String user = PluginFacts.child(jdbc, "user");
        if (user != null) values.put("jdbc-user", user);
        String password = PluginFacts.child(jdbc, "password");
        if (password != null) values.put("jdbc-password", password);
        report.warning("`" + ARTIFACT + "` reads a live database; `[jooq]` keeps that as `jdbc-url`, and the"
                + " driver is `driver = \"group:artifact:version\"` — name the migrations that built the schema in"
                + " `sql`, since they are the step's cache key.");
        return true;
    }
}
