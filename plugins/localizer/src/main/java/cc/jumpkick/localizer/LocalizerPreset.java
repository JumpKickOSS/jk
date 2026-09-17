// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.localizer;

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

/**
 * The localizer preset's code layer: {@code [localizer]} becomes one {@link GeneratorEntry} —
 * {@link LocalizerMain}, shipped in this worker's own jar, over the localizer plugin's closure and
 * the module's {@code Messages.properties} bundles, into the generate stage, contributed as sources
 * — run by the generator plugin's step. The generated classes read
 * {@code org.jvnet.localizer:localizer} at run time, a dependency the module declares itself.
 */
public final class LocalizerPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the localizer plugin's jar. */
    static final String TOOL = "localizer";

    static final String TOOL_COORDINATE = "org.jvnet.localizer:localizer-maven-plugin";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-localizer", "##JKLZ:");
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
     * The generator entry the table expands to: one glob per resource directory as the inputs, the
     * shim's options as the arguments, every directory as an absolute path at the end.
     */
    static GeneratorEntry entry(PluginConfig config, List<Path> classpath) {
        String mask = config.stringOpt("mask").orElse("Messages.properties");
        List<String> resources = config.stringList("resources");
        if (resources.isEmpty()) resources = List.of("src/main/resources");
        List<String> inputs = new ArrayList<>();
        List<String> args = new ArrayList<>(List.of("--out", "${out}", "--mask", mask));
        config.stringOpt("encoding").ifPresent(encoding -> args.addAll(List.of("--encoding", encoding)));
        config.stringOpt("key-pattern").ifPresent(pattern -> args.addAll(List.of("--key-pattern", pattern)));
        if (config.bool("strict-types", false)) args.add("--strict-types");
        if (config.bool("access-modifier-annotations", false)) args.add("--access-modifier-annotations");
        for (String dir : resources) {
            inputs.add(dir + "/**/" + mask);
            args.add("${module.dir}/" + dir);
        }
        return new GeneratorEntry(
                "localizer",
                TOOL,
                TOOL_COORDINATE,
                LocalizerMain.class.getName(),
                inputs,
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/localizer",
                classpath);
    }

    /** This worker's own jar (or classes directory), which carries {@link LocalizerMain}. */
    static List<Path> ownJar() {
        CodeSource source = LocalizerPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the localizer worker cannot name its own jar for the shim's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the localizer worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
