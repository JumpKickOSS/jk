// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import java.nio.file.Path;
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
 * {@code maven-replacer-plugin} ({@code replacer}) beside {@code protobuf-maven-plugin}: an
 * execution rewriting the files under the protobuf plugin's output — Hadoop's, turning {@code
 * com.google.protobuf} in the generated sources into the package its shading jar carries the
 * runtime under — is {@code [protobuf] replace}, each {@code <replacement>}'s token and value in
 * declaration order, a literal token ({@code <regex>false</regex>}) quoted for the regular
 * expression the step reads. An execution over any other directory — the module's own sources,
 * the generated test sources — is a row: jk rewrites nothing in place, and the table covers the
 * main generation alone. {@code <skip>true</skip>} leaves an execution out, as Maven does.
 */
final class ReplacerPlugin {

    static final String ARTIFACT = "replacer";

    /** The {@code replace} rules for the protobuf table, and whether the plugin was read at all. */
    record Mapped(Map<String, String> replace, boolean consumed) {
        static final Mapped NONE = new Mapped(Map.of(), false);
    }

    private ReplacerPlugin() {}

    /**
     * @param outputRoots the module-relative roots the protobuf plugin fills
     * @param baseDir the module directory, or null for a POM with no file behind it
     */
    static Mapped map(Model model, Set<String> outputRoots, @Nullable Path baseDir, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Map<String, String> replace = new LinkedHashMap<>();
        Xpp3Dom own = plugin.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom config = merged(execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null, own);
            if (EnvValues.parseBool(PluginFacts.child(config, "skip")).orElse(false)) continue;
            String id = execution.getId() == null ? "default" : execution.getId();
            String declared = PluginFacts.child(config, "basedir");
            String basedir = declared == null ? "" : SourceTreePlugins.moduleRelative(declared, baseDir);
            if (!covers(outputRoots, basedir)) {
                report.warning("`" + ARTIFACT + "` execution `" + id + "` rewrites the files under `"
                        + (basedir.isEmpty() ? "." : basedir) + "` in place, outside the protobuf plugin's output;"
                        + " `[protobuf] replace` rewrites the generated main sources alone and jk edits no other"
                        + " file, so those compile as written.");
                continue;
            }
            rules(config, replace, id, report);
        }
        return new Mapped(Map.copyOf(replace), true);
    }

    /** The execution's configuration over the plugin's, the execution's values winning. */
    private static Xpp3Dom merged(@Nullable Xpp3Dom execution, @Nullable Xpp3Dom plugin) {
        if (execution == null) return plugin == null ? new Xpp3Dom("configuration") : plugin;
        if (plugin == null) return execution;
        Xpp3Dom merged = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(execution), plugin);
        return merged == null ? execution : merged;
    }

    /** True when {@code basedir} holds a protobuf output root or lies inside one. */
    private static boolean covers(Set<String> outputRoots, String basedir) {
        for (String root : outputRoots) {
            if (basedir.isEmpty() || root.equals(basedir)) return true;
            if (root.startsWith(basedir + "/") || basedir.startsWith(root + "/")) return true;
        }
        return false;
    }

    /** Each {@code <replacement>} (or the single {@code <token>}/{@code <value>}) as a rule; the other forms are rows. */
    private static void rules(Xpp3Dom config, Map<String, String> replace, String id, ImportReport.Builder report) {
        boolean regex = EnvValues.parseBool(PluginFacts.child(config, "regex")).orElse(true);
        Xpp3Dom replacements = config.getChild("replacements");
        if (replacements != null) {
            for (Xpp3Dom replacement : replacements.getChildren()) rule(replacement, regex, replace);
        }
        if (config.getChild("token") != null) rule(config, regex, replace);
        for (String form : List.of("tokenValueMap", "file", "xpath", "tokenFile", "valueFile")) {
            if (config.getChild(form) != null) {
                report.warning("`" + ARTIFACT + "` execution `" + id + "` `<" + form + ">` has no `[protobuf] replace`"
                        + " form; only `<replacements>` (or one `<token>` and `<value>`) is written.");
            }
        }
        if (config.getChild("excludes") != null) {
            report.warning("`" + ARTIFACT + "` execution `" + id + "` `<excludes>` have no `[protobuf] replace` key;"
                    + " every generated file is rewritten. A proto `[protobuf] exclude` names is not generated.");
        }
    }

    private static void rule(Xpp3Dom holder, boolean regex, Map<String, String> replace) {
        String token = PluginFacts.text(holder.getChild("token"));
        if (token == null || token.isEmpty()) return;
        String value = PluginFacts.text(holder.getChild("value"));
        if (value == null) value = "";
        if (regex) {
            replace.put(token, value);
        } else {
            replace.put(Pattern.quote(token), Matcher.quoteReplacement(value));
        }
    }
}
