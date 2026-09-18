// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jooq;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The jOOQ preset's code layer: {@code [jooq]} becomes one {@link GeneratorEntry} — {@link
 * JooqMain}, shipped in this worker's own jar, over jOOQ's code generator and the module's DDL
 * scripts (or a live schema over JDBC), into the generate stage, contributed as sources — run by
 * the generator plugin's step. The generated classes read {@code org.jooq:jooq} at run time, a
 * dependency the module declares itself at the generator's version.
 */
public final class JooqPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the generator's closure. */
    static final String TOOL = "jooq-codegen";

    static final String TOOL_COORDINATE = "org.jooq:jooq-codegen";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-jooq", "##JKJOOQ:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.task(entry(ctx.config(), ctx.project(), ownJar()).task());
    }

    /**
     * The generator entry the table expands to: the scripts as the inputs and the cache key, the
     * main's options as the arguments, the scripts themselves at the end. A live database is the
     * {@code jdbc-*} keys; the scripts still key the step, since they are what built that schema.
     */
    static GeneratorEntry entry(PluginConfig config, ProjectFacts project, List<Path> classpath) {
        String sql = config.stringOpt("sql").orElse("src/main/resources/db/migration/**/*.sql");
        List<String> args = new ArrayList<>(List.of(
                "--out",
                "${out}",
                "--package",
                config.stringOpt("package").orElse(project.group() + ".jooq"),
                "--schema",
                config.stringOpt("schema").orElse("PUBLIC"),
                "--name-case",
                config.stringOpt("name-case").orElse("as_is"),
                "--includes",
                config.stringOpt("includes").orElse(".*")));
        config.stringOpt("excludes").filter(s -> !s.isEmpty()).ifPresent(e -> args.addAll(List.of("--excludes", e)));
        if (!config.bool("records", true)) args.add("--no-records");
        if (config.bool("pojos", false)) args.add("--pojos");
        if (config.bool("daos", false)) args.add("--daos");
        if (config.bool("fluent-setters", false)) args.add("--fluent-setters");
        for (Map.Entry<String, String> property : config.stringMap("properties").entrySet()) {
            args.addAll(List.of("--property", property.getKey() + "=" + property.getValue()));
        }
        config.stringOpt("jdbc-url").ifPresent(url -> {
            args.addAll(List.of("--jdbc-url", url));
            config.stringOpt("jdbc-user").ifPresent(user -> args.addAll(List.of("--jdbc-user", user)));
            config.stringOpt("jdbc-password").ifPresent(pw -> args.addAll(List.of("--jdbc-password", pw)));
        });
        args.add("${inputs}");
        return new GeneratorEntry(
                "jooq",
                TOOL,
                TOOL_COORDINATE,
                JooqMain.class.getName(),
                List.of(sql),
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/jooq",
                classpath,
                List.of());
    }

    /** This worker's own jar (or classes directory), which carries {@link JooqMain}. */
    static List<Path> ownJar() {
        CodeSource source = JooqPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the jooq worker cannot name its own jar for the main's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the jooq worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
