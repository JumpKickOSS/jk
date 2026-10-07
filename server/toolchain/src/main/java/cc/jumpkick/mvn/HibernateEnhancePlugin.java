// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.PluginConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * Hibernate's build-time enhancement is the {@code [hibernate]} table: {@code
 * hibernate-enhance-maven-plugin} (Hibernate 5 and 6) or {@code hibernate-maven-plugin}'s {@code
 * enhance} goal (7 and later) writes {@code enhance = true}, and each {@code enable*} switch its
 * key, written only when it differs from the plugins' own default. The enhancer is the project's
 * Hibernate, so the plugin's version has no key.
 */
final class HibernateEnhancePlugin {

    static final String ENHANCE_PLUGIN = "hibernate-enhance-maven-plugin";
    static final String MAVEN_PLUGIN = "hibernate-maven-plugin";

    /** Maven switch, jk key, and the default both plugins give it. */
    private record Switch(String maven, String key, boolean byDefault) {}

    private static final List<Switch> SWITCHES = List.of(
            new Switch("enableLazyInitialization", "lazy-initialization", true),
            new Switch("enableDirtyTracking", "dirty-tracking", true),
            new Switch("enableAssociationManagement", "association-management", false),
            new Switch("enableExtendedEnhancement", "extended-enhancement", false));

    private HibernateEnhancePlugin() {}

    static @Nullable PluginConfig map(Model model, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(model, ENHANCE_PLUGIN)
                .or(() -> PluginFacts.plugin(model, MAVEN_PLUGIN).filter(HibernateEnhancePlugin::enhances));
        if (plugin.isEmpty()) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enhance", true);
        for (Switch s : SWITCHES) {
            Boolean declared = null;
            for (Xpp3Dom config : PluginFacts.configurations(plugin.get())) {
                Optional<Boolean> value = EnvValues.parseBool(PluginFacts.child(config, s.maven()));
                if (value.isPresent()) declared = value.get();
            }
            if (declared != null && declared != s.byDefault()) values.put(s.key(), declared);
        }
        report.warning("`" + plugin.get().getArtifactId() + "` is `[hibernate] enhance = true`: the module's own"
                + " Hibernate enhances the compiled entity classes after compile, and the tests and the jar"
                + " use the enhanced classes.");
        return new PluginConfig("hibernate", values);
    }

    /** Whether {@code hibernate-maven-plugin} runs its {@code enhance} goal; its other goals are not enhancement. */
    private static boolean enhances(Plugin plugin) {
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals().contains("enhance")) return true;
        }
        return false;
    }
}
