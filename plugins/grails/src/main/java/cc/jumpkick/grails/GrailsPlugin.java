// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.grails;

import cc.jumpkick.boot.BootJarPackager;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageContext;
import cc.jumpkick.plugin.build.PackageExtension;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grails build plugin: the {@code grails-jar} packager. A Grails 8 executable jar IS a Spring
 * Boot 4.1 jar (JarLauncher layout), so the body delegates to {@link BootJarPackager}; the
 * grails-specific shaping (BOM, compiler args, grails-app roots) is manifest data.
 */
public final class GrailsPlugin implements Plugin, PackageExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-grails", "##JKGR:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void pack(PackageContext ctx) {
        ctx.inputs(In.classes(), In.runtimeEntries(), In.config()).produce("grails-jar", GrailsPlugin::produceJar);
    }

    private static void produceJar(PackageIo io) throws Exception {
        PluginConfig grails = io.config();
        String grailsVersion = grails.string("version");
        String bootVersion = grails.stringOpt("boot-version").orElse("4.1.0");
        String startClass = io.project().mainClass();
        if (startClass == null || startClass.isBlank()) {
            throw new IOException("no application main class — the grails jar needs a Start-Class");
        }
        Path loaderJar = io.extra("spring-boot-loader")
                .orElseThrow(() -> new IOException("spring-boot-loader artifact missing from the packager inputs"));

        List<BootJarPackager.Lib> libs = new ArrayList<>();
        for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
            libs.add(new BootJarPackager.Lib(entry.fileName(), entry.jar(), entry.snapshot()));
        }

        io.label("package " + io.artifactPath().getFileName() + " (grails)");
        Map<String, String> attributes = new LinkedHashMap<>(io.project().manifest());
        attributes.put("Grails-Version", grailsVersion);
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        io.classesDir(),
                        libs,
                        loaderJar,
                        io.artifactPath(),
                        startClass,
                        bootVersion,
                        attributes,
                        Map.of(),
                        null,
                        List.of(),
                        0L));
    }
}
