// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.taglib;

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
 * The taglib preset's code layer: {@code [taglib]} becomes one {@link GeneratorEntry} —
 * {@link TaglibMain}, shipped in this worker's own jar and needing no tool, over the module's
 * resource directories, into the generate stage, contributed as sources — run by the generator
 * plugin's step. The generated interfaces read {@code org.kohsuke.stapler:stapler-groovy} at
 * compile time, a dependency the module declares itself.
 */
public final class TaglibPreset implements Plugin, BuildExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-taglib", "##JKTL:");
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
     * The generator entry the table expands to: every Jelly view under the resource directories as
     * the inputs, the encoding as an option, every directory as an absolute path at the end.
     */
    static GeneratorEntry entry(PluginConfig config, List<Path> classpath) {
        List<String> resources = config.stringList("resources");
        if (resources.isEmpty()) resources = List.of("src/main/resources");
        List<String> inputs = new ArrayList<>();
        List<String> args = new ArrayList<>(List.of("--out", "${out}"));
        config.stringOpt("encoding").ifPresent(encoding -> args.addAll(List.of("--encoding", encoding)));
        for (String dir : resources) {
            inputs.add(dir + "/**/*.jelly");
            args.add("${module.dir}/" + dir);
        }
        return new GeneratorEntry(
                "taglib",
                null,
                null,
                TaglibMain.class.getName(),
                inputs,
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/taglib",
                classpath,
                List.of());
    }

    /** This worker's own jar (or classes directory), which carries {@link TaglibMain}. */
    static List<Path> ownJar() {
        CodeSource source = TaglibPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the taglib worker cannot name its own jar for the main's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the taglib worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
