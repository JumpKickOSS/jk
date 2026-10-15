// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
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
 * depends on, written only when they differ from the table's default. {@code options} and the jlink
 * settings have no key and are reported.
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
            for (String unmapped : List.of("options", "jlinkImageName", "launcher", "jlinkZipName")) {
                if (config.getChild(unmapped) != null) {
                    report.warning("`" + PLUGIN + "` <" + unmapped + "> has no `[javafx]` key and was not imported;"
                            + " JVM flags for the test JVM go in `[test] jvm-args`.");
                }
            }
        }
        report.warning("`" + PLUGIN + "` is `[javafx]`: the OpenJFX modules ride the module path of the test JVM"
                + " and `jk run`. A fat jar runs with `java -jar` only through a main class that does not extend"
                + " `javafx.application.Application`.");
        return new PluginConfig("javafx", values);
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
