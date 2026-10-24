// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.host.Os;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Closed {@code ${…}} interpolation for manifest contributions: {@code ${config.<key>}},
 * project/kotlin fields, {@code ${host.os}} / {@code ${host.os-arch}}, and inside a
 * {@code per-entry} tool declaration {@code ${entry.name}} / {@code ${entry.<key>}}. Unknown vars
 * fail at {@link #validate} (install time), not mid-build.
 */
public final class Interpolation {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]*)}");

    /** The {@code ${entry.name}} variable: an entry's own table name, never one of its keys. */
    static final String ENTRY_NAME = "name";

    private Interpolation() {}

    /** One {@code [entries]} sub-table as a template scope: its name and its validated values. */
    public record Entry(String name, Map<String, Object> values) {}

    /** As {@link #validate(String, Set, Set, String)} outside a per-entry declaration. */
    static void validate(String template, Set<String> schemaKeys, String where) {
        validate(template, schemaKeys, null, where);
    }

    /**
     * Manifest-load validation: every referenced variable must exist in the closed vocabulary.
     * {@code entryKeys} is the entry schema's key set inside a {@code per-entry} declaration and
     * null everywhere else, where {@code ${entry.…}} is an error.
     */
    static void validate(String template, Set<String> schemaKeys, @Nullable Set<String> entryKeys, String where) {
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            String var = m.group(1);
            if (var.startsWith("config.")) {
                String key = var.substring("config.".length());
                if (!schemaKeys.contains(key)) {
                    throw new JkBuildParseException(
                            where + " references ${" + var + "} but the [schema] declares no `" + key + "`");
                }
                continue;
            }
            if (var.startsWith("entry.")) {
                String key = var.substring("entry.".length());
                if (entryKeys == null) {
                    throw new JkBuildParseException(
                            where + " references ${" + var + "} outside a per-entry = true step-dependency");
                }
                if (!key.equals(ENTRY_NAME) && !entryKeys.contains(key)) {
                    throw new JkBuildParseException(
                            where + " references ${" + var + "} but the entry schema declares no `" + key + "`");
                }
                continue;
            }
            switch (var) {
                case "kotlin.version",
                        "project.group",
                        "project.name",
                        "project.version",
                        "host.os",
                        "host.os-arch" -> {}
                default ->
                    throw new JkBuildParseException(where + " references unknown variable ${" + var
                            + "} (known: config.<schema-key>, entry.name, entry.<entry-key>, kotlin.version,"
                            + " project.group, project.name, project.version, host.os, host.os-arch)");
            }
        }
    }

    /**
     * True when every {@code ${entry.<key>}} the template names has a value in {@code entry} —
     * false for a per-entry declaration over an optional key this entry leaves unset, which is
     * then not declared for it. A null template names nothing and provides.
     */
    static boolean entryProvides(@Nullable String template, Entry entry) {
        if (template == null) return true;
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            String var = m.group(1);
            if (!var.startsWith("entry.")) continue;
            String key = var.substring("entry.".length());
            if (!key.equals(ENTRY_NAME) && entry.values().get(key) == null) return false;
        }
        return true;
    }

    /**
     * True when every {@code ${config.<key>}} the template names has a value in {@code config} —
     * false for a declaration over an optional key the table leaves unset, which then declares
     * nothing instead of failing the build.
     */
    static boolean configProvides(String template, PluginConfig config) {
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            String var = m.group(1);
            if (var.startsWith("config.") && config.values().get(var.substring("config.".length())) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * The machine a template's {@code ${host.os}} ({@code linux} / {@code osx} / {@code windows})
     * and {@code ${host.os-arch}} (protoc style: {@code linux-x86_64}, {@code osx-aarch_64}, …)
     * stand for.
     */
    public record Host(String os, String osArch) {

        /** The platforms a lock pins a host-following tool for. */
        public static final List<Host> LOCKED = List.of(
                new Host("linux", "linux-x86_64"),
                new Host("linux", "linux-aarch_64"),
                new Host("osx", "osx-x86_64"),
                new Host("osx", "osx-aarch_64"),
                new Host("windows", "windows-x86_64"));

        /** This machine. */
        public static Host current() {
            return new Host(hostOs(), hostOsArch());
        }
    }

    /** True when {@code template} names {@code ${host.os}} or {@code ${host.os-arch}}: it resolves per platform. */
    public static boolean followsHost(@Nullable String template) {
        return template != null && (template.contains("${host.os}") || template.contains("${host.os-arch}"));
    }

    /** Host OS classifier: {@code linux} / {@code osx} / {@code windows}. */
    static String hostOs() {
        if (Os.isDarwin()) return "osx";
        if (Os.isWindows()) return "windows";
        return "linux";
    }

    /** Host OS+arch classifier (protoc style: {@code linux-x86_64}, {@code osx-aarch_64}, …). */
    static String hostOsArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String normalized =
                switch (arch) {
                    case "amd64", "x86_64" -> "x86_64";
                    case "aarch64", "arm64" -> "aarch_64";
                    case "ppc64le" -> "ppcle_64";
                    case "s390x" -> "s390_64";
                    default -> arch;
                };
        return hostOs() + "-" + normalized;
    }

    /**
     * Resolve a validated template. Null {@code kotlinVersion} makes {@code ${kotlin.version}} an
     * evaluation error.
     */
    public static String resolve(
            @Nullable String template, PluginConfig config, Project project, @Nullable String kotlinVersion) {
        return resolve(template, config, project, kotlinVersion, null);
    }

    /** A template that is one variable and nothing else: {@code ${config.key}} or {@code ${entry.key}}. */
    private static final Pattern ONE_VAR = Pattern.compile("^\\$\\{(config|entry)\\.([^}]+)}$");

    /**
     * {@code template} resolved to every value it stands for: a template that is one {@code
     * ${config.<key>}} or {@code ${entry.<key>}} over a string-list key is that list, element by
     * element; any other template is the one string {@link #resolve} makes of it.
     */
    public static List<String> resolveAll(
            String template,
            PluginConfig config,
            Project project,
            @Nullable String kotlinVersion,
            @Nullable Entry entry) {
        return resolveAll(template, config, project, kotlinVersion, entry, Host.current());
    }

    /** As {@link #resolveAll(String, PluginConfig, Project, String, Entry)} for {@code host}. */
    public static List<String> resolveAll(
            String template,
            PluginConfig config,
            Project project,
            @Nullable String kotlinVersion,
            @Nullable Entry entry,
            Host host) {
        Matcher one = ONE_VAR.matcher(template);
        if (one.matches()) {
            Object raw = one.group(1).equals("config")
                    ? config.values().get(one.group(2))
                    : entry == null ? null : entry.values().get(one.group(2));
            if (raw instanceof List<?> list) {
                List<String> out = new ArrayList<>(list.size());
                for (Object element : list) out.add(String.valueOf(element));
                return out;
            }
        }
        Matcher embedded = VAR.matcher(template);
        while (embedded.find()) {
            String var = embedded.group(1);
            if (!var.startsWith("config.")) continue;
            if (!(config.values().get(var.substring("config.".length())) instanceof List<?> list)) continue;
            // A list key inside a longer template is one template per element, each resolved in turn.
            List<String> out = new ArrayList<>(list.size());
            for (Object element : list) {
                String expanded =
                        template.substring(0, embedded.start()) + element + template.substring(embedded.end());
                out.addAll(resolveAll(expanded, config, project, kotlinVersion, entry, host));
            }
            return out;
        }
        return List.of(resolve(template, config, project, kotlinVersion, entry, host));
    }

    /**
     * {@code template} once per element of the first string-list {@code ${config.<key>}} it names,
     * that variable replaced by the element — {@code plugins/${config.plugins}/lib/*.jar} over
     * {@code ["java", "gradle"]} is two strings; with no list key it is {@link #resolve}'s one string.
     */
    public static List<String> resolveEach(String template, PluginConfig config, Project project) {
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            String var = m.group(1);
            if (!var.startsWith("config.")) continue;
            if (!(config.values().get(var.substring("config.".length())) instanceof List<?> list)) continue;
            List<String> out = new ArrayList<>(list.size());
            for (Object element : list) {
                String one = template.substring(0, m.start()) + element + template.substring(m.end());
                out.addAll(resolveEach(one, config, project));
            }
            return out;
        }
        return List.of(resolve(template, config, project, null, null));
    }

    /** As {@link #resolve(String, PluginConfig, Project, String)} inside one entry's scope. */
    public static String resolve(
            @Nullable String template,
            PluginConfig config,
            Project project,
            @Nullable String kotlinVersion,
            @Nullable Entry entry) {
        return resolve(template, config, project, kotlinVersion, entry, Host.current());
    }

    private static String resolve(
            @Nullable String template,
            PluginConfig config,
            Project project,
            @Nullable String kotlinVersion,
            @Nullable Entry entry,
            Host host) {
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String value;
            if (var.startsWith("config.")) {
                String key = var.substring("config.".length());
                Object raw = config.values().get(key);
                if (raw == null) {
                    throw new JkBuildParseException("[" + config.id() + "] contribution needs ${" + var
                            + "} but the table leaves `" + key + "` unset");
                }
                value = String.valueOf(raw);
            } else if (var.startsWith("entry.")) {
                String key = var.substring("entry.".length());
                Object raw = entry == null
                        ? null
                        : key.equals(ENTRY_NAME) ? entry.name() : entry.values().get(key);
                if (raw == null) {
                    throw new JkBuildParseException("[" + config.id() + "] contribution needs ${" + var + "} but "
                            + (entry == null
                                    ? "no entry is in scope"
                                    : "[" + config.id() + "." + entry.name() + "] leaves `" + key + "` unset"));
                }
                value = String.valueOf(raw);
            } else {
                value = switch (var) {
                    case "kotlin.version" -> kotlinVersion;
                    case "project.group" -> project.group();
                    case "project.name" -> project.name();
                    case "project.version" -> project.version();
                    case "host.os" -> host.os();
                    case "host.os-arch" -> host.osArch();
                    default -> null; // unreachable: validate() ran at manifest load
                };
                if (value == null) {
                    throw new JkBuildParseException("[" + config.id() + "] contribution needs ${" + var
                            + "} but this build has no value for it");
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
