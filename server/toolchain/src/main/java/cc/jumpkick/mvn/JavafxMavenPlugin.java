// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.model.PluginConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code javafx-maven-plugin} is the {@code [javafx]} table. Its {@code mainClass} is the
 * application's main (read with the other plugins'); the modules are the OpenJFX artifacts the POM
 * depends on, written only when they differ from the table's default. {@code options} has no key
 * there and is reported. The plugin's jlink settings are the {@code [jlink]} table ({@link #jlink}).
 */
final class JavafxMavenPlugin {

    static final String PLUGIN = "javafx-maven-plugin";

    /** The table's default {@code modules}. */
    static final List<String> DEFAULT_MODULES = List.of("javafx.controls", "javafx.fxml");

    private static final String OPENJFX = "org.openjfx";

    private JavafxMavenPlugin() {}

    static @Nullable PluginConfig map(Model model, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(model, PLUGIN);
        if (plugin.isEmpty()) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> modules = modules(model.getDependencies());
        if (!modules.isEmpty() && !modules.equals(DEFAULT_MODULES)) values.put("modules", modules);
        for (Xpp3Dom config : PluginFacts.configurations(plugin.get())) {
            if (config.getChild("options") != null) {
                report.warning(PLUGIN + " <options> not imported; put test JVM flags in [test] jvm-args");
            }
        }
        report.warning(
                PLUGIN + " became [javafx]. For java -jar, use a main class that doesn't extend" + " Application");
        return new PluginConfig("javafx", values);
    }

    /** The settings of the plugin's {@code javafx:jlink} goal; any one of them means the build links an image. */
    static final List<String> JLINK_SETTINGS = List.of(
            "jlinkImageName",
            "launcher",
            "jlinkZipName",
            "stripDebug",
            "compress",
            "noHeaderFiles",
            "noManPages",
            "bindServices",
            "jlinkVerbose",
            "jlinkExecutable",
            "ignoreSigningInformation");

    /**
     * {@code javafx:jlink} as {@code [jlink]}, or {@code null} when the plugin carries no jlink
     * setting: its {@code stripDebug} (the plugin's default is off) and its {@code options}, which
     * the plugin's launcher runs with. The goal never starts the application, and training a
     * cache would open a window on the build host, so the table writes neither a start nor a
     * cache; the image and launcher names are jk's.
     */
    static @Nullable Jlink jlink(Model model, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(model, PLUGIN);
        if (plugin.isEmpty()) return null;
        boolean declared = false;
        boolean stripDebug = false;
        List<String> jvmArgs = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin.get())) {
            for (String setting : JLINK_SETTINGS) declared |= config.getChild(setting) != null;
            Xpp3Dom strip = config.getChild("stripDebug");
            if (strip != null)
                stripDebug =
                        Boolean.parseBoolean(String.valueOf(strip.getValue()).trim());
            Xpp3Dom options = config.getChild("options");
            if (options != null) {
                for (Xpp3Dom option : options.getChildren()) {
                    String value = option.getValue();
                    if (value != null && !value.isBlank() && !jvmArgs.contains(value.trim())) jvmArgs.add(value.trim());
                }
            }
        }
        if (!declared) return null;
        for (Xpp3Dom config : PluginFacts.configurations(plugin.get())) {
            for (String named : List.of("jlinkImageName", "launcher", "jlinkZipName")) {
                if (config.getChild(named) != null) {
                    report.warning(PLUGIN + " <" + named + "> not imported: jk writes the image to target/<name>-jri/"
                            + " with bin/start as its launcher, and zips nothing");
                }
            }
        }
        report.warning(PLUGIN + " jlink became [jlink] without a startup cache; aot-cache = true trains one by"
                + " starting the application, which needs a display on the build host");
        return new Jlink(List.of(), stripDebug, Jlink.AotCache.OFF, jvmArgs, List.of(), 0, false, Map.of());
    }

    /** {@code org.openjfx:javafx-controls} → {@code javafx.controls}, in declaration order. */
    static List<String> modules(List<Dependency> dependencies) {
        List<String> out = new ArrayList<>();
        for (Dependency d : dependencies) {
            if (!OPENJFX.equals(d.getGroupId()) || !d.getArtifactId().startsWith("javafx-")) continue;
            String module = d.getArtifactId().replace('-', '.');
            if (!out.contains(module)) out.add(module);
        }
        return out;
    }
}
