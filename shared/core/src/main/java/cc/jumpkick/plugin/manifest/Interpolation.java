// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginConfig;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closed {@code ${…}} interpolation for manifest contributions: {@code ${config.<key>}},
 * project/kotlin fields, {@code ${host.os}} / {@code ${host.os-arch}}. Unknown vars fail at
 * {@link #validate} (install time), not mid-build.
 */
final class Interpolation {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]*)}");

    private Interpolation() {}

    /** Manifest-load validation: every referenced variable must exist in the closed vocabulary. */
    static void validate(String template, Set<String> schemaKeys, String where) {
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
            switch (var) {
                case "kotlin.version",
                        "project.group",
                        "project.name",
                        "project.version",
                        "host.os",
                        "host.os-arch" -> {}
                default ->
                    throw new JkBuildParseException(where + " references unknown variable ${" + var
                            + "} (known: config.<schema-key>, kotlin.version, project.group, project.name,"
                            + " project.version, host.os, host.os-arch)");
            }
        }
    }

    /** Host OS classifier: {@code linux} / {@code osx} / {@code windows}. */
    static String hostOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        if (os.contains("win")) return "windows";
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
    static String resolve(String template, PluginConfig config, JkBuild.Project project, String kotlinVersion) {
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
            } else {
                value = switch (var) {
                    case "kotlin.version" -> kotlinVersion;
                    case "project.group" -> project.group();
                    case "project.name" -> project.name();
                    case "project.version" -> project.version();
                    case "host.os" -> hostOs();
                    case "host.os-arch" -> hostOsArch();
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
