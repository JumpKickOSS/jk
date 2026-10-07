// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.openapi;

import cc.jumpkick.config.EnvValues;
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
     * swagger annotations, no {@code JsonNullable}. {@code [openapi] options} overrides any of them,
     * and {@code useSpringBoot4 = "true"} replaces the Boot 3 switch.
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

    /**
     * The generator entry the table expands to. {@code package} is the root every generated package
     * derives from; {@code api-package}, {@code model-package} and {@code invoker-package} each
     * replace their derived name.
     */
    /** {@code flag k=v,k2=v2}, in the table's order; nothing when the map is empty. */
    private static void mapping(List<String> args, String flag, Map<String, String> pairs) {
        if (pairs.isEmpty()) return;
        List<String> joined = new ArrayList<>(pairs.size());
        pairs.forEach((k, v) -> joined.add(k + "=" + v));
        args.add(flag);
        args.add(String.join(",", joined));
    }

    static GeneratorEntry entry(PluginConfig config, ProjectFacts project) {
        String generator = config.string("generator");
        String pkg = config.stringOpt("package").orElse(project.group() + ".api");
        List<String> args = new ArrayList<>(List.of(
                "generate",
                "-i",
                "${in}",
                "-g",
                generator,
                "-o",
                "${out}",
                "--api-package",
                config.stringOpt("api-package").orElse(pkg),
                "--model-package",
                config.stringOpt("model-package").orElse(pkg + ".model"),
                "--invoker-package",
                config.stringOpt("invoker-package").orElse(pkg),
                "--package-name",
                pkg));
        config.stringOpt("library").ifPresent(v -> args.addAll(List.of("--library", v)));
        config.stringOpt("model-name-prefix").ifPresent(v -> args.addAll(List.of("--model-name-prefix", v)));
        config.stringOpt("model-name-suffix").ifPresent(v -> args.addAll(List.of("--model-name-suffix", v)));
        mapping(args, "--import-mappings", config.stringMap("import-mappings"));
        mapping(args, "--type-mappings", config.stringMap("type-mappings"));
        Map<String, String> options = new LinkedHashMap<>();
        if (generator.equals("spring")) {
            for (String pair : SPRING_DEFAULTS) {
                int eq = pair.indexOf('=');
                options.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        Map<String, String> declared = config.stringMap("options");
        options.putAll(declared);
        // The spring generator refuses both Boot switches at once: a table asking for Boot 4
        // drops the preset's Boot 3 default unless it set that one too.
        if (EnvValues.parseBool(declared.get("useSpringBoot4")).orElse(false)
                && !declared.containsKey("useSpringBoot3")) {
            options.remove("useSpringBoot3");
        }
        // The generator checks the annotation library against the documentation provider: a table
        // that names a provider leaves the library to the generator's choice for that provider.
        if (declared.containsKey("documentationProvider") && !declared.containsKey("annotationLibrary")) {
            options.remove("annotationLibrary");
        }
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
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/openapi",
                List.of(),
                List.of(),
                null);
    }
}
