// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.antlr;

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
import java.util.Map;

/**
 * The ANTLR preset's code layer: {@code [antlr]} becomes one {@link GeneratorEntry} —
 * {@link AntlrMain}, shipped in this worker's own jar, over the ANTLR tool's closure and the
 * module's {@code .g4} grammars, into the generate stage, contributed as sources — run by the
 * generator plugin's step. The generated parsers read {@code org.antlr:antlr4-runtime} at run
 * time, a dependency the module declares itself at the tool's version.
 */
public final class AntlrPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the ANTLR tool. */
    static final String TOOL = "antlr";

    static final String TOOL_COORDINATE = "org.antlr:antlr4";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-antlr", "##JKANTLR:");
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
     * The generator entry the table expands to: every grammar under {@code src} as the inputs, the
     * main's options as the arguments, the grammar files themselves at the end.
     */
    static GeneratorEntry entry(PluginConfig config, List<Path> classpath) {
        String src = config.stringOpt("src").orElse("src/main/antlr4");
        String lib = config.stringOpt("lib").orElse(src + "/imports");
        List<String> args = new ArrayList<>(
                List.of("--out", "${out}", "--src", "${module.dir}/" + src, "--lib", "${module.dir}/" + lib));
        config.stringOpt("package").ifPresent(pkg -> args.addAll(List.of("--package", pkg)));
        if (!config.bool("listener", true)) args.add("--no-listener");
        if (config.bool("visitor", false)) args.add("--visitor");
        config.stringOpt("encoding").ifPresent(encoding -> args.addAll(List.of("--encoding", encoding)));
        for (Map.Entry<String, String> option : config.stringMap("options").entrySet()) {
            args.addAll(List.of("--arg", "-D" + option.getKey() + "=" + option.getValue()));
        }
        for (String argument : config.stringList("arguments")) args.addAll(List.of("--arg", argument));
        args.add("${inputs}");
        return new GeneratorEntry(
                "antlr",
                TOOL,
                TOOL_COORDINATE,
                AntlrMain.class.getName(),
                List.of(src + "/**/*.g4"),
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/antlr",
                classpath,
                List.of());
    }

    /** This worker's own jar (or classes directory), which carries {@link AntlrMain}. */
    static List<Path> ownJar() {
        CodeSource source = AntlrPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the antlr worker cannot name its own jar for the main's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the antlr worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
