// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.PluginConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The code generators. {@code openapi-generator-maven-plugin} is the {@code [openapi]} preset:
 * {@code <inputSpec>} is {@code spec}, {@code <generatorName>} is {@code generator}, the model
 * package's root is {@code package} with an api, model or invoker package it does not derive as
 * its own key, {@code <configOptions>} and
 * {@code <additionalProperties>} are {@code options}, and the plugin's version is the CLI's. A spec
 * the POM reads over HTTP is fetched into {@code api/} once, since a generator step's inputs are
 * files whose content is the step's cache key. What the preset has no key for is a row naming
 * the option. The generator's output directory is reported back so the {@code add-source} that
 * put it on Maven's compile path is not written as an {@code extra-src} root.
 * {@code protobuf-maven-plugin} is {@link ProtobufPlugin}'s {@code [protobuf]} table,
 * {@code localizer-maven-plugin} {@link LocalizerPlugin}'s {@code [localizer]}, {@code antlr4-maven-plugin}
 * {@link AntlrPlugin}'s {@code [antlr]}, {@code maven-hpi-plugin}'s taglib goal {@link TaglibPlugin}'s
 * {@code [taglib]}, {@code avro-maven-plugin} {@link AvroPlugin}'s {@code [avro]}, the JAXB compiler
 * plugins {@link JaxbPlugin}'s {@code [jaxb]}, {@code jooq-codegen-maven} {@link JooqPlugin}'s
 * {@code [jooq]}, {@code wire-maven-plugin} {@link WirePlugin}'s
 * {@code [generate.wire]} entry; the GraphQL codegen plugins are a row naming the recipe.
 */
final class GeneratorPlugins {

    static final String OPENAPI = "openapi-generator-maven-plugin";

    /**
     * The generator tables a POM's plugins add, and the module-relative output roots the POM's
     * generators fill, each with what an {@code add-source} root inside it is (the build-helper row).
     */
    record Generators(
            @Nullable PluginConfig openapi,
            @Nullable PluginConfig protobuf,
            @Nullable PluginConfig localizer,
            @Nullable PluginConfig antlr,
            @Nullable PluginConfig taglib,
            @Nullable PluginConfig avro,
            @Nullable PluginConfig jaxb,
            @Nullable PluginConfig jooq,
            @Nullable PluginConfig generate,
            Map<String, String> outputRoots,
            /** Plugins another mapping consumed, which get no "not imported" row of their own. */
            Set<String> consumedPlugins) {

        /** Every table the POM's generators add, in declaration order, the absent ones left out. */
        List<PluginConfig> tables() {
            List<PluginConfig> tables = new ArrayList<>();
            for (PluginConfig table :
                    new PluginConfig[] {openapi, protobuf, localizer, antlr, taglib, avro, jaxb, jooq, generate}) {
                if (table != null) tables.add(table);
            }
            return tables;
        }
    }

    private static final String DGS_CODEGEN = "graphqlcodegen-maven-plugin";
    private static final String GRAPHQL_JAVA_CODEGEN = "graphql-codegen-maven-plugin";

    /** What an {@code add-source} root inside the OpenAPI output is, for the build-helper row. */
    private static final String OPENAPI_ADD_SOURCE_ROW = "the OpenAPI generator's output; `[openapi]` folds the"
            + " generated sources into the compile itself, so no `extra-src` root is written";

    /** The {@code <configuration>} children the preset's keys cover; anything else is a row. */
    private static final Set<String> COVERED = Set.of(
            "inputSpec",
            "generatorName",
            "output",
            "apiPackage",
            "modelPackage",
            "invokerPackage",
            "packageName",
            "configOptions",
            "additionalProperties");

    private static final String DEFAULT_OUTPUT = "target/generated-sources/openapi";

    private GeneratorPlugins() {}

    static Generators map(Model model, PomImporter.RemoteFile remote, ImportReport.Builder report) {
        Map<String, String> outputRoots = new LinkedHashMap<>();
        PluginConfig openapi = null;
        Optional<Plugin> plugin = PluginFacts.plugin(model, OPENAPI);
        if (plugin.isPresent()) {
            Path baseDir = model.getProjectDirectory() == null
                    ? null
                    : model.getProjectDirectory().toPath();
            List<Xpp3Dom> configs = generateConfigs(plugin.get(), report);
            String output = value(configs, "output");
            String outputRoot = output == null ? DEFAULT_OUTPUT : SourceTreePlugins.moduleRelative(output, baseDir);
            outputRoots.put(outputRoot, OPENAPI_ADD_SOURCE_ROW);
            openapi = mapOpenApi(plugin.get(), configs, baseDir, remote, report);
        }
        LocalizerPlugin.Mapped localizer = LocalizerPlugin.map(model, report);
        outputRoots.putAll(localizer.outputRoots());
        ProtobufPlugin.Mapped protobuf = ProtobufPlugin.map(model, report);
        outputRoots.putAll(protobuf.outputRoots());
        AntlrPlugin.Mapped antlr = AntlrPlugin.map(model, report);
        outputRoots.putAll(antlr.outputRoots());
        TaglibPlugin.Mapped taglib = TaglibPlugin.map(model, report);
        outputRoots.putAll(taglib.outputRoots());
        AvroPlugin.Mapped avro = AvroPlugin.map(model, report);
        outputRoots.putAll(avro.outputRoots());
        JaxbPlugin.Mapped jaxb = JaxbPlugin.map(model, report);
        outputRoots.putAll(jaxb.outputRoots());
        JooqPlugin.Mapped jooq = JooqPlugin.map(model, report);
        outputRoots.putAll(jooq.outputRoots());
        WirePlugin.Mapped wire = WirePlugin.map(model, report);
        outputRoots.putAll(wire.outputRoots());
        PluginConfig generate = wire.entry() == null
                ? null
                : new PluginConfig("generator", Map.of(PluginConfig.ENTRIES, Map.of(WirePlugin.ENTRY, wire.entry())));
        reportGraphQl(model, report);
        Set<String> consumed = new LinkedHashSet<>(wire.consumed());
        consumed.addAll(taglib.consumed());
        consumed.addAll(protobuf.consumed());
        return new Generators(
                openapi,
                protobuf.table(),
                localizer.table(),
                antlr.table(),
                taglib.table(),
                avro.table(),
                jaxb.table(),
                jooq.table(),
                generate,
                Collections.unmodifiableMap(outputRoots),
                Collections.unmodifiableSet(consumed));
    }

    /** The GraphQL code generators: a row each naming where the step lands. */
    private static void reportGraphQl(Model model, ImportReport.Builder report) {
        if (PluginFacts.plugin(model, DGS_CODEGEN).isPresent()) {
            report.warning("`" + DGS_CODEGEN + "` (DGS codegen) is a `[generate.<name>]` recipe over"
                    + " `com.netflix.graphql.dgs.codegen:graphql-dgs-codegen-core`'s command line — see the GraphQL"
                    + " section of docs/user/generate.md for the entry; nothing was written.");
        }
        if (PluginFacts.plugin(model, GRAPHQL_JAVA_CODEGEN).isPresent()) {
            report.warning("`" + GRAPHQL_JAVA_CODEGEN + "` (graphql-java-codegen) has no command line a"
                    + " `[generate.<name>]` entry could run — its Maven and Gradle plugins are the only drivers — so"
                    + " that step stays under `jk mvn`; see the GraphQL section of docs/user/generate.md.");
        }
    }

    /**
     * The plugin's configuration followed by its first {@code generate} execution's, so the
     * execution's values win; a second {@code generate} execution is a row, since one
     * {@code [openapi]} table runs the generator once.
     */
    private static List<Xpp3Dom> generateConfigs(Plugin plugin, ImportReport.Builder report) {
        List<Xpp3Dom> configs = new ArrayList<>();
        if (plugin.getConfiguration() instanceof Xpp3Dom dom) configs.add(dom);
        List<String> others = new ArrayList<>();
        boolean taken = false;
        for (PluginExecution execution : plugin.getExecutions()) {
            if (!execution.getGoals().contains("generate")) continue;
            if (taken) {
                others.add(execution.getId());
                continue;
            }
            taken = true;
            if (execution.getConfiguration() instanceof Xpp3Dom dom) configs.add(dom);
        }
        if (!others.isEmpty()) {
            report.warning("`" + OPENAPI + "` runs `generate` more than once (" + String.join(", ", others)
                    + " besides the first); `[openapi]` is one run, so give each further run its own"
                    + " `[generate.<name>]` entry.");
        }
        return configs;
    }

    private static @Nullable PluginConfig mapOpenApi(
            Plugin plugin,
            List<Xpp3Dom> configs,
            @Nullable Path baseDir,
            PomImporter.RemoteFile remote,
            ImportReport.Builder report) {
        String inputSpec = value(configs, "inputSpec");
        String generator = value(configs, "generatorName");
        if (inputSpec == null || generator == null) {
            report.error("`" + OPENAPI + "` declares no resolvable `<"
                    + (inputSpec == null ? "inputSpec" : "generatorName")
                    + ">`; no `[openapi]` table was written. The preset needs `spec` and `generator`.");
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spec", spec(inputSpec, baseDir, remote, report));
        values.put("generator", generator);
        values.putAll(packages(configs, report));
        String version = PluginFacts.usable(plugin.getVersion());
        if (version != null) {
            values.put("version", version);
        } else {
            report.warning("`" + OPENAPI + "` has no resolvable version; `[openapi] version` is `latest`, which"
                    + " `jk lock` pins to the newest openapi-generator-cli release.");
        }
        Map<String, String> options = options(configs);
        if (!options.isEmpty()) values.put("options", options);
        reportUncovered(configs, report);
        if ("spring".equals(generator)) {
            report.warning("`[openapi] generator = \"spring\"` applies jk's defaults (interfaceOnly, useSpringBoot3,"
                    + " useJakartaEe, documentationProvider = none, annotationLibrary = none, openApiNullable ="
                    + " false, useTags) under your `<configOptions>`; the Maven plugin's own defaults generate"
                    + " springdoc and swagger annotations and JsonNullable fields, so set those keys in `options`"
                    + " if the code depends on them.");
        }
        return new PluginConfig("openapi", values);
    }

    /**
     * The module-relative spec path. A {@code file} path is relativized; an {@code http(s)} URL is
     * fetched into {@code api/<file>} beside the manifest (kept when already there), and the row
     * says so — or says what to download when the fetch fails.
     */
    private static String spec(
            String inputSpec, @Nullable Path baseDir, PomImporter.RemoteFile remote, ImportReport.Builder report) {
        String lower = inputSpec.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return SourceTreePlugins.moduleRelative(inputSpec, baseDir);
        }
        String file = inputSpec.substring(inputSpec.lastIndexOf('/') + 1);
        if (file.isBlank() || file.contains("?")) file = "openapi.yaml";
        String spec = "api/" + file;
        if (baseDir != null && Files.exists(baseDir.resolve(spec))) {
            report.warning("`<inputSpec>` is " + inputSpec + "; `" + spec
                    + "` is already there, so `[openapi] spec` names it and the URL was not fetched.");
            return spec;
        }
        try {
            byte[] body = remote.fetch(URI.create(inputSpec));
            if (baseDir == null) throw new IOException("the POM has no directory to write into");
            Path target = baseDir.resolve(spec);
            Files.createDirectories(target.getParent());
            Files.write(target, body);
            report.warning("`<inputSpec>` is " + inputSpec + "; a generator step's inputs are files, so the spec was"
                    + " fetched to `" + spec + "` and `[openapi] spec` names it. Check the file in; a newer"
                    + " release of the spec is a new copy.");
        } catch (IOException e) {
            report.error("`<inputSpec>` is " + inputSpec + ", which could not be fetched (" + e.getMessage()
                    + "); `[openapi] spec = \"" + spec + "\"` was written — save the spec there before building.");
        }
        return spec;
    }

    /**
     * {@code package} is the root: the model package without its {@code .model}, else the api
     * package, else the invoker package. An api, model or invoker package that root does not
     * derive is written as its own key; a {@code <packageName>} the root does not equal is a row.
     */
    private static Map<String, String> packages(List<Xpp3Dom> configs, ImportReport.Builder report) {
        String api = value(configs, "apiPackage");
        String model = value(configs, "modelPackage");
        String invoker = value(configs, "invokerPackage");
        String packageName = value(configs, "packageName");
        String pkg = model == null ? null : model.endsWith(".model") ? model.substring(0, model.length() - 6) : model;
        if (pkg == null) pkg = api;
        if (pkg == null) pkg = invoker != null ? invoker : packageName;
        Map<String, String> keys = new LinkedHashMap<>();
        if (pkg == null) return keys;
        keys.put("package", pkg);
        if (api != null && !api.equals(pkg)) keys.put("api-package", api);
        if (model != null && !model.equals(pkg + ".model")) keys.put("model-package", model);
        if (invoker != null && !invoker.equals(pkg)) keys.put("invoker-package", invoker);
        if (packageName != null && !packageName.equals(pkg)) {
            report.warning("`<packageName>` " + packageName + " has no `[openapi]` key; the generator's"
                    + " `--package-name` is `package` (" + pkg + ").");
        }
        return keys;
    }

    /** {@code <configOptions>} children and {@code <additionalProperties>} pairs, in declaration order. */
    private static Map<String, String> options(List<Xpp3Dom> configs) {
        Map<String, String> options = new LinkedHashMap<>();
        for (Xpp3Dom config : configs) {
            Xpp3Dom configOptions = config.getChild("configOptions");
            if (configOptions != null) {
                for (Xpp3Dom option : configOptions.getChildren()) {
                    String value = PluginFacts.usable(option.getValue());
                    if (value != null) options.put(option.getName(), value);
                }
            }
            String additional = PluginFacts.child(config, "additionalProperties");
            if (additional == null) continue;
            for (String pair : additional.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0)
                    options.put(
                            pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return options;
    }

    /** Every configuration element the preset has no key for, as one row. */
    private static void reportUncovered(List<Xpp3Dom> configs, ImportReport.Builder report) {
        Set<String> names = new LinkedHashSet<>();
        for (Xpp3Dom config : configs) {
            for (Xpp3Dom child : config.getChildren()) {
                if (!COVERED.contains(child.getName())) names.add("`<" + child.getName() + ">`");
            }
        }
        if (names.isEmpty()) return;
        report.warning("`" + OPENAPI + "` " + String.join(", ", names) + " have no `[openapi]` key; the preset"
                + " runs the generator with `spec`, `generator`, `package`, `version` and `options`, and a"
                + " `[generate.<name>]` entry spells any other argument.");
    }

    /** The last usable value of {@code name} across {@code configs}, so an execution's wins over the plugin's. */
    private static @Nullable String value(List<Xpp3Dom> configs, String name) {
        String found = null;
        for (Xpp3Dom config : configs) {
            String v = PluginFacts.child(config, name);
            if (v != null) found = v;
        }
        return found;
    }
}
