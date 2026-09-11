// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.grails;

import cc.jumpkick.boot.BootJarInputs;
import cc.jumpkick.boot.BootJarPackager;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageContext;
import cc.jumpkick.plugin.build.PackageExtension;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
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

    static void produceJar(PackageIo io) throws Exception {
        BootJarInputs inputs = BootJarInputs.read(io);

        io.label("package " + io.artifactPath().getFileName() + " (grails)");
        Map<String, String> attributes = new LinkedHashMap<>(io.project().manifest());
        // The one attribute a Grails jar carries that a Boot jar does not. `[grails] version` is a
        // BOM selector like any other, but Grails resolves no runtime artifact whose version is
        // the Grails line, so this records what was asked for and says so.
        attributes.put("Grails-Version", io.config().string("version"));
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        io.classesDir(),
                        inputs.libs(),
                        inputs.loaderJar(),
                        io.artifactPath(),
                        inputs.startClass(),
                        inputs.bootVersion(),
                        attributes,
                        Map.of(),
                        new byte[0],
                        List.of(),
                        0L));
    }
}
