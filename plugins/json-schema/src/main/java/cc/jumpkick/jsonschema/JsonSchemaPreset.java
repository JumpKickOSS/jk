// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonschema;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON Schema preset's code layer: {@code [json-schema]} becomes one {@link GeneratorEntry},
 * {@link JsonSchemaMain} over jsonschema2pojo's closure and the module's schema files, into the
 * generate stage, contributed as sources, run by the generator plugin's step. The generated
 * classes carry the annotations of {@code annotation-style}, whose library the module declares.
 */
public final class JsonSchemaPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the generator's closure. */
    static final String TOOL = "jsonschema2pojo-cli";

    static final String TOOL_COORDINATE = "org.jsonschema2pojo:jsonschema2pojo-cli";

    static final String DEFAULT_SRC = "src/main/resources/json";

    /** Each boolean key that turns a jsonschema2pojo flag on, with the flag. */
    private static final Map<String, String> SWITCHES = Map.of(
            "builders", "-b",
            "constructors", "-c",
            "jsr303", "-303",
            "jakarta-validation", "--useJakartaValidation",
            "serializable", "-sl",
            "optional-getters", "-o",
            "long-integers", "-l",
            "big-decimals", "-i",
            "title-as-classname", "-ut");

    /** Each string key with the flag that takes its value. */
    private static final Map<String, String> VALUES = Map.of(
            "package", "-p",
            "date-time-type", "-dtt",
            "date-type", "-dt",
            "time-type", "-tt",
            "encoding", "-e");

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-json-schema", "##JKJSONSCHEMA:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.task(entry(ctx.config(), ownJar()).task());
    }

    /**
     * The generator entry the table expands to: every schema file under {@code src} as the inputs,
     * {@code src} itself as the root whose subdirectories are subpackages, and the table's keys as
     * jsonschema2pojo's flags, in a stable order.
     */
    static GeneratorEntry entry(PluginConfig config, List<Path> classpath) {
        String src = config.stringOpt("src").orElse(DEFAULT_SRC);
        String sourceType = config.stringOpt("source-type").orElse("jsonschema").toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>(List.of("--root", "${module.dir}/" + src, "-t", "${out}"));
        args.addAll(List.of("-T", sourceType.toUpperCase(Locale.ROOT)));
        args.addAll(List.of(
                "-a", config.stringOpt("annotation-style").orElse("jackson").toUpperCase(Locale.ROOT)));
        VALUES.keySet().stream().sorted().forEach(key -> config.stringOpt(key)
                .ifPresent(value -> args.addAll(List.of(VALUES.get(key), value))));
        SWITCHES.keySet().stream().sorted().forEach(key -> {
            if (config.bool(key, false)) args.add(SWITCHES.get(key));
        });
        if (config.bool("additional-properties", true)) args.add("-D");
        args.add("--");
        args.add("${inputs}");
        List<String> globs = sourceType.startsWith("yaml")
                ? List.of(src + "/**/*.yaml", src + "/**/*.yml")
                : List.of(src + "/**/*.json");
        return new GeneratorEntry(
                "json-schema",
                TOOL,
                TOOL_COORDINATE,
                JsonSchemaMain.class.getName(),
                globs,
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/json-schema",
                classpath,
                List.of(),
                null);
    }

    /** This worker's own jar (or classes directory), which carries {@link JsonSchemaMain}. */
    static List<Path> ownJar() {
        CodeSource source = JsonSchemaPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the json-schema worker cannot name its own jar for the main's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the json-schema worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
