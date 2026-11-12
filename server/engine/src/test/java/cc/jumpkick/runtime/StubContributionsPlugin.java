// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.plugin.build.TaskSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * A plugin worker the forecast tests fork from this test classpath ({@link StubContributionsPlugin#jar}):
 * {@code [stub-contributions] sources = true} declares a step whose generated Java joins the main
 * compile, {@code test-env = true} one that hands the test JVM a flag and a classpath directory.
 */
public final class StubContributionsPlugin implements Plugin, BuildExtension {

    /** The table a module declares the plugin under. */
    static final String TABLE = "stub-contributions";

    /** The protocol prefix the engine selects this plugin by. */
    static final String PREFIX = "##JKSTUBC:";

    /** The flag the test JVM gets. */
    static final String JVM_ARG = "-Dstub.contributions=on";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-stub-contributions", PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        if (ctx.config().bool("sources", false)) {
            ctx.task(TaskSpec.named("stub-sources")
                    .inputs(In.config())
                    .outputs("gen")
                    .contributesSources("gen")
                    .run(StubContributionsPlugin::sources));
        }
        if (ctx.config().bool("test-env", false)) {
            ctx.task(TaskSpec.named("stub-test-env")
                    .inputs(In.config())
                    .outputs("env")
                    .contributesTestJvmArgs("env/jvm.args")
                    .contributesTestClasspath("env/cp")
                    .run(StubContributionsPlugin::testEnv));
        }
    }

    private static void sources(TaskExec exec) throws Exception {
        Path file = exec.outputDir("gen").resolve("com/example/Generated.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                public final class Generated {
                    public static final String NAME = "generated";

                    private Generated() {}
                }
                """);
    }

    private static void testEnv(TaskExec exec) throws Exception {
        Path env = exec.outputDir("env");
        Files.writeString(env.resolve("jvm.args"), JVM_ARG + "\n", StandardCharsets.UTF_8);
        Path cp = Files.createDirectories(env.resolve("cp"));
        Files.writeString(cp.resolve("stub-contributions.properties"), "on=true\n", StandardCharsets.UTF_8);
    }

    /** The plugin's manifest, its worker named {@code worker}: the jar {@link #jar} writes stands in for it. */
    static String descriptor(String worker) {
        return """
                [plugin]
                id    = "%s"
                table = "%s"

                [code]
                worker          = "%s"
                protocol-prefix = "%s"

                [schema]
                sources  = { type = "bool", default = false }
                test-env = { type = "bool", default = false }
                """.formatted(TABLE, TABLE, worker, PREFIX);
    }

    /**
     * A worker jar at {@code out} that launches the SDK host over this JVM's own classpath and
     * registers this plugin: nothing is built or published for it.
     */
    static Path jar(Path out) throws IOException {
        StringBuilder classPath = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            if (!classPath.isEmpty()) classPath.append(' ');
            classPath.append(Path.of(entry).toAbsolutePath().toUri());
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, PluginLoader.WORKER_MAIN);
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());
        Files.createDirectories(out.getParent());
        try (var file = Files.newOutputStream(out);
                var jar = new JarOutputStream(file, manifest)) {
            jar.putNextEntry(new JarEntry("META-INF/services/" + Plugin.class.getName()));
            jar.write((StubContributionsPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        // The worker's classpath is its manifest's: the POM beside it declares nothing more.
        String name = out.getFileName().toString();
        Files.writeString(out.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>stub-contributions</artifactId>
                  <version>1.0</version>
                </project>
                """);
        return out;
    }
}
