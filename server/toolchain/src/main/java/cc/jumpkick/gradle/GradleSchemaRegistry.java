// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.PluginConfig;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The Confluent schema-registry Gradle plugin as {@code [schema-registry]}: its URL, each subject
 * its {@code download} block names at its version ({@code latest} when it names none), and a
 * basic-auth credential the build reads from the environment as a credential table under the id
 * {@code schema-registry}. A subject pattern, and registering or checking schemas, are rows.
 */
final class GradleSchemaRegistry {

    static final String PLUGIN = "com.github.imflog.kafka-schema-registry-gradle-plugin";

    /** The repository id the registry's credential is kept under. */
    static final String REPOSITORY = "schema-registry";

    /** The table, null when there is nothing to download, and the directories the plugin wrote. */
    record Mapped(@Nullable PluginConfig table, Set<String> outputs) {
        static final Mapped NONE = new Mapped(null, Set.of());
    }

    private GradleSchemaRegistry() {}

    static Mapped map(
            GradleModel.SchemaRegistry registry,
            ImportReport.Builder local,
            Consumer<PluginRules.Credential> credentials) {
        Map<String, String> subjects = new LinkedHashMap<>();
        Set<String> outputs = new LinkedHashSet<>();
        for (GradleModel.Download d : registry.subjects()) {
            if (d.regex()) {
                local.warning("schemaRegistry: pattern " + d.subject() + " not imported; list its subjects");
                continue;
            }
            subjects.put(
                    d.subject(), d.version() == null ? "latest" : d.version().toString());
            outputs.add(d.output());
        }
        if (registry.registers())
            local.warning("schemaRegistry: register and compatibility not run; jk only downloads");
        String url = registry.url();
        if (subjects.isEmpty() || url == null) return Mapped.NONE;
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("url", url);
        table.put("subjects", subjects);
        if (registry.auth()) {
            table.put("repository", REPOSITORY);
            credentials.accept(
                    new PluginRules.Credential(REPOSITORY, url, registry.usernameEnv(), registry.passwordEnv()));
        }
        return new Mapped(new PluginConfig("schema-registry", table), Set.copyOf(outputs));
    }

    /** Whether {@code dir} is one of {@code outputs} or inside one. */
    static boolean downloaded(String dir, Set<String> outputs) {
        for (String out : outputs) {
            if (dir.equals(out) || dir.startsWith(out + "/")) return true;
        }
        return false;
    }
}
