// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.PluginConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code org.hibernate.orm} Gradle plugin's {@code hibernate { enhancement { ... } }} block is
 * {@code [hibernate] enhance = true}; each {@code enable*} switch it sets is its key, written only
 * when it differs from the plugin's own default. Applied without an {@code enhancement} block the
 * plugin enhances nothing, and neither does the import.
 */
final class HibernateGradle {

    static final String PLUGIN = "org.hibernate.orm";

    /** Gradle property, jk key, and the plugin's own default. */
    private record Switch(String gradle, String key, boolean byDefault) {}

    private static final List<Switch> SWITCHES = List.of(
            new Switch("enableLazyInitialization", "lazy-initialization", true),
            new Switch("enableDirtyTracking", "dirty-tracking", true),
            new Switch("enableAssociationManagement", "association-management", false),
            new Switch("enableExtendedEnhancement", "extended-enhancement", false));

    private HibernateGradle() {}

    static @Nullable PluginConfig map(Set<String> applied, String script, ImportReport.Builder report) {
        if (!applied.contains(PLUGIN)) return null;
        Optional<String> enhancement = GradleScriptText.extractBlock(script, "hibernate")
                .flatMap(body -> GradleScriptText.extractBlock(body, "enhancement"));
        if (enhancement.isEmpty()) {
            report.warning("`" + PLUGIN + "` is applied without an `enhancement` block, so it enhances nothing;"
                    + " no `[hibernate]` table is written.");
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enhance", true);
        for (Switch s : SWITCHES) {
            Boolean declared = switchValue(enhancement.get(), s.gradle());
            if (declared != null && declared != s.byDefault()) values.put(s.key(), declared);
        }
        return new PluginConfig("hibernate", values);
    }

    /** {@code name = true}, {@code name.set(true)} or Groovy's {@code name true}; null when unset. */
    static @Nullable Boolean switchValue(String body, String name) {
        Matcher m = Pattern.compile(
                        "\\b" + Pattern.quote(name) + "\\b\\s*(?:=\\s*|\\.set\\s*\\(\\s*|\\s+)(true|false)\\b")
                .matcher(body);
        Boolean last = null;
        while (m.find()) last = Boolean.parseBoolean(m.group(1));
        return last;
    }
}
