// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.model.PluginConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code kafka-schema-registry-maven-plugin}'s {@code download} goal is {@code [schema-registry]}:
 * the first of {@code <schemaRegistryUrls>} is {@code url}, each of {@code <subjectPatterns>} that
 * names one subject is a subject at the matching entry of {@code <versions>} ({@code latest} past
 * its end), and a {@code <userInfoConfig>} the build reads from the environment becomes a
 * credential table naming those variables, under the id {@code schema-registry}. A pattern that
 * matches more than one subject, and the register and compatibility goals, are rows.
 */
final class SchemaRegistryPlugin {

    static final String ARTIFACT = "kafka-schema-registry-maven-plugin";

    /** The repository id the registry's credential is kept under. */
    static final String REPOSITORY = "schema-registry";

    private static final Pattern LITERAL_SUBJECT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ENV = Pattern.compile("\\$\\{env\\.([A-Za-z0-9_]+)}");
    private static final Set<String> DOWNLOAD_ONLY = Set.of("download");

    /**
     * The table (null without a download), the module-relative directory it downloaded into (null
     * without one), and the credential table (null when the build sends none).
     */
    record Mapped(
            @Nullable PluginConfig table,
            @Nullable String output,
            UserConfigEditor.@Nullable CredentialTable credential) {
        static final Mapped NONE = new Mapped(null, null, null);
    }

    private SchemaRegistryPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        for (PluginExecution execution : plugin.getExecutions()) {
            for (String goal : execution.getGoals()) {
                if (!DOWNLOAD_ONLY.contains(goal))
                    report.warning(ARTIFACT + " " + goal + ": not run; jk only downloads");
            }
        }
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        String url = null;
        String userInfo = null;
        String output = null;
        List<String> patterns = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            List<String> urls = params(config, "schemaRegistryUrls");
            if (!urls.isEmpty()) url = urls.getFirst();
            // Read raw: an ${env.NAME} the shell does not define stays a placeholder, and names the variable.
            String info = PluginFacts.text(config.getChild("userInfoConfig"));
            if (info != null && !info.isBlank()) userInfo = info.strip();
            String out = PluginFacts.child(config, "outputDirectory");
            if (out != null) output = SourceTreePlugins.moduleRelative(out, baseDir);
            List<String> p = params(config, "subjectPatterns");
            if (!p.isEmpty()) patterns = p;
            List<String> v = params(config, "versions");
            if (!v.isEmpty()) versions = v;
        }
        Map<String, String> subjects = new LinkedHashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            String pattern = patterns.get(i);
            if (!LITERAL_SUBJECT.matcher(pattern).matches()) {
                report.warning(ARTIFACT + ": pattern " + pattern + " not imported; list its subjects");
                continue;
            }
            subjects.put(pattern, i < versions.size() ? versions.get(i) : "latest");
        }
        if (url == null || subjects.isEmpty()) return Mapped.NONE;
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("url", url);
        table.put("subjects", subjects);
        UserConfigEditor.CredentialTable credential = null;
        if (userInfo != null) {
            table.put("repository", REPOSITORY);
            credential = credential(url, userInfo, report);
        }
        return new Mapped(new PluginConfig("schema-registry", table), output, credential);
    }

    /**
     * {@code key:secret} as a table of {@code ${env:NAME}} references: each half the POM spells
     * {@code ${env.NAME}}, or whose value a variable of the shell holds. Null, with a row, when a
     * half is neither.
     */
    private static UserConfigEditor.@Nullable CredentialTable credential(
            String url, String userInfo, ImportReport.Builder report) {
        int colon = userInfo.indexOf(':');
        String username = colon < 0 ? null : variable(userInfo.substring(0, colon));
        String password = colon < 0 ? null : variable(userInfo.substring(colon + 1));
        if (username == null || password == null) {
            report.warning(REPOSITORY + ": no credential found; run jk repo login " + REPOSITORY);
            return null;
        }
        try {
            return new UserConfigEditor.CredentialTable(
                    REPOSITORY,
                    new URI(url),
                    Map.of("username", "${env:" + username + "}", "password", "${env:" + password + "}"));
        } catch (URISyntaxException e) {
            report.warning(REPOSITORY + ": " + url + " is not a valid URI; credential skipped");
            return null;
        }
    }

    /** The variable {@code half} names or holds, or null. */
    private static @Nullable String variable(String half) {
        Matcher m = ENV.matcher(half);
        if (m.matches()) return m.group(1);
        if (half.length() < 8) return null;
        return BuildEnv.callerEnv().entrySet().stream()
                .filter(e -> e.getValue().equals(half))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static List<String> params(Xpp3Dom config, String name) {
        Xpp3Dom list = config.getChild(name);
        if (list == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Xpp3Dom child : list.getChildren()) {
            String text = PluginFacts.text(child);
            if (text != null && !text.isBlank()) out.add(text.strip());
        }
        return out;
    }
}
