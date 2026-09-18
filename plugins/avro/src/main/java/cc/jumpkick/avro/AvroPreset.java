// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.avro;

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
 * The Avro preset's code layer: {@code [avro]} becomes one {@link GeneratorEntry} — {@link
 * AvroMain}, shipped in this worker's own jar, over the Avro compiler's closure and the module's
 * schema, protocol and IDL files, into the generate stage, contributed as sources — run by the
 * generator plugin's step. The generated classes read {@code org.apache.avro:avro} at run time, a
 * dependency the module declares itself at the compiler's version.
 */
public final class AvroPreset implements Plugin, BuildExtension {

    /** The step-dependency artifact the manifest declares for the compiler's closure. */
    static final String TOOL = "avro-compiler";

    static final String TOOL_COORDINATE = "org.apache.avro:avro-compiler";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-avro", "##JKAVRO:");
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
     * The generator entry the table expands to: every schema, protocol and IDL file under {@code
     * src} as the inputs, the main's options as the arguments, the files themselves at the end.
     */
    static GeneratorEntry entry(PluginConfig config, List<Path> classpath) {
        String src = config.stringOpt("src").orElse("src/main/avro");
        List<String> args = new ArrayList<>(List.of("--out", "${out}"));
        config.stringOpt("string-type").ifPresent(type -> args.addAll(List.of("--string-type", type)));
        config.stringOpt("field-visibility")
                .ifPresent(visibility -> args.addAll(List.of("--field-visibility", visibility)));
        if (!config.bool("setters", true)) args.add("--no-setters");
        if (config.bool("optional-getters", false)) args.add("--optional-getters");
        if (config.bool("decimal-logical-type", false)) args.add("--decimal-logical-type");
        config.stringOpt("encoding").ifPresent(encoding -> args.addAll(List.of("--encoding", encoding)));
        args.add("${inputs}");
        return new GeneratorEntry(
                "avro",
                TOOL,
                TOOL_COORDINATE,
                AvroMain.class.getName(),
                List.of(src + "/**/*.avsc", src + "/**/*.avpr", src + "/**/*.avdl"),
                null,
                args,
                GeneratorEntry.Contribution.SOURCES,
                "generated/avro",
                classpath,
                List.of());
    }

    /** This worker's own jar (or classes directory), which carries {@link AvroMain}. */
    static List<Path> ownJar() {
        CodeSource source = AvroPreset.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the avro worker cannot name its own jar for the main's classpath");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the avro worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
