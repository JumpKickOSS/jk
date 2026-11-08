// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.asyncapi;

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

/**
 * The AsyncAPI preset's code layer: {@code [asyncapi]} becomes one {@link GeneratorEntry}, {@link
 * AsyncApiMain} over jsonschema2pojo's closure and the module's AsyncAPI documents, into the
 * generate stage, contributed as sources, run by the generator plugin's step.
 */
public final class AsyncApiPreset implements Plugin, BuildExtension {

    static final String TOOL = "jsonschema2pojo-cli";

    static final String TOOL_COORDINATE = "org.jsonschema2pojo:jsonschema2pojo-cli";

    static final String DEFAULT_SRC = "src/main/asyncapi/asyncapi.yaml";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-asyncapi", "##JKASYNCAPI:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.task(entry(ctx.config(), ownJar()).task());
    }

    /** The generator entry the table expands to: the documents as the inputs, the keys as jsonschema2pojo's flags. */
    static GeneratorEntry entry(PluginConfig config, List<Path> classpath) {
        List<String> src = config.stringList("src");
        if (src.isEmpty()) src = List.of(DEFAULT_SRC);
        List<String> args = new ArrayList<>(List.of("-t", "${out}"));
        args.addAll(List.of(
                "-a", config.stringOpt("annotation-style").orElse("jackson").toUpperCase(Locale.ROOT)));
        config.stringOpt("package").ifPresent(p -> args.addAll(List.of("-p", p)));
        config.stringOpt("date-time-type").ifPresent(t -> args.addAll(List.of("-dtt", t)));
        if (config.bool("jsr303", false)) args.add("-303");
        if (config.bool("jakarta-validation", false)) args.add("--useJakartaValidation");
        if (config.bool("additional-properties", true)) args.add("-D");
        args.add("--");
        args.add("${inputs}");
        return new GeneratorEntry(
                "asyncapi",
                TOOL,
                TOOL_COORDINATE,
                AsyncApiMain.class.getName(),
                src,
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/asyncapi",
                classpath,
                List.of(),
                null);
    }

    /** This worker's own jar (or classes directory), which carries {@link AsyncApiMain}. */
    static List<Path> ownJar() {
        CodeSource source = AsyncApiPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the asyncapi worker cannot name its own jar for the main's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the asyncapi worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
