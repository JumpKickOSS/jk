// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jaxb;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The JAXB preset's code layer: {@code [jaxb]} becomes one {@link GeneratorEntry} — xjc's {@code
 * Driver} over the module's schema directory, into the generate stage, contributed as sources —
 * run by the generator plugin's step. The generated classes read {@code jakarta.xml.bind-api} at
 * compile time and a JAXB runtime at run time, dependencies the module declares itself.
 */
public final class JaxbPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the compiler's closure. */
    static final String TOOL = "jaxb-xjc";

    static final String TOOL_COORDINATE = "org.glassfish.jaxb:jaxb-xjc";

    /** xjc's command-line entry point; it exits with the run's status. */
    static final String MAIN = "com.sun.tools.xjc.Driver";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-jaxb", "##JKJAXB:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.task(entry(ctx.config()).task());
    }

    /**
     * The generator entry the table expands to: every schema under {@code src} and every binding
     * as the inputs, xjc's options as the arguments, the schema directory itself at the end — xjc
     * compiles a directory's schemas in one run. {@code -no-header} keeps the generated sources
     * free of a timestamp, so an unchanged schema is a byte-identical output.
     */
    static GeneratorEntry entry(PluginConfig config) {
        String src = config.stringOpt("src").orElse("src/main/xsd");
        List<String> inputs = new ArrayList<>(List.of(src + "/**/*.xsd"));
        List<String> args = new ArrayList<>(List.of("-d", "${out}", "-no-header", "-quiet"));
        config.stringOpt("package").ifPresent(pkg -> args.addAll(List.of("-p", pkg)));
        for (String binding : config.stringList("bindings")) {
            inputs.add(binding.endsWith(".xjb") ? binding : binding + "/**/*.xjb");
            args.addAll(List.of("-b", "${module.dir}/" + binding));
        }
        config.stringOpt("encoding").ifPresent(encoding -> args.addAll(List.of("-encoding", encoding)));
        if (config.bool("extension", false)) args.add("-extension");
        args.addAll(config.stringList("arguments"));
        args.add("${module.dir}/" + src);
        return new GeneratorEntry(
                "jaxb",
                TOOL,
                TOOL_COORDINATE,
                MAIN,
                inputs,
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/jaxb",
                List.of(),
                List.of());
    }
}
