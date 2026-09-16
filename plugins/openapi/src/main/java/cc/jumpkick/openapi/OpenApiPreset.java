// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.openapi;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenAPI preset's code layer: {@code [openapi]} becomes one {@link GeneratorEntry} —
 * openapi-generator-cli's {@code generate} over the spec, into the generate stage, contributed as
 * sources — run by the generator plugin's step. The table's keys are the arguments a user would
 * otherwise spell out under {@code [generate.openapi]}.
 */
public final class OpenApiPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the CLI jar. */
    static final String TOOL = "openapi-generator-cli";

    static final String TOOL_COORDINATE = "org.openapitools:openapi-generator-cli";

    /**
     * What the {@code spring} generator needs to produce an interface-only API a Boot module
     * compiles with no extra libraries: no controllers or application class, Jakarta imports, no
     * swagger annotations, no {@code JsonNullable}. {@code [openapi] options} overrides any of them.
     */
    private static final List<String> SPRING_DEFAULTS = List.of(
            "interfaceOnly=true",
            "useSpringBoot3=true",
            "useJakartaEe=true",
            "documentationProvider=none",
            "annotationLibrary=none",
            "openApiNullable=false",
            "useTags=true");

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-openapi", "##JKOA:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.task(entry(ctx.config(), ctx.project()).task());
    }

    /** The generator entry the table expands to. */
    static GeneratorEntry entry(PluginConfig config, ProjectFacts project) {
        String generator = config.string("generator");
        String pkg = config.stringOpt("package").orElse(project.group() + ".api");
        List<String> args = new ArrayList<>(List.of(
                "generate",
                "-i", "${in}",
                "-g", generator,
                "-o", "${out}",
                "--api-package", pkg,
                "--model-package", pkg + ".model",
                "--invoker-package", pkg,
                "--package-name", pkg));
        Map<String, String> options = new LinkedHashMap<>();
        if (generator.equals("spring")) {
            for (String pair : SPRING_DEFAULTS) {
                int eq = pair.indexOf('=');
                options.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        options.putAll(config.stringMap("options"));
        if (!options.isEmpty()) {
            List<String> pairs = new ArrayList<>(options.size());
            options.forEach((k, v) -> pairs.add(k + "=" + v));
            args.add("--additional-properties");
            args.add(String.join(",", pairs));
        }
        return new GeneratorEntry(
                "openapi",
                TOOL,
                TOOL_COORDINATE,
                null,
                List.of(config.stringOpt("spec").orElse("api/*.yaml")),
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/openapi");
    }
}
