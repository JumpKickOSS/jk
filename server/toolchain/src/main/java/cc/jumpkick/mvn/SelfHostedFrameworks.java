// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A framework table on a module of the framework's own reactor. {@code [quarkus] version} implies
 * the platform BOM {@code io.quarkus.platform:quarkus-bom} at that version, which repositories
 * publish for releases only; a reactor that builds {@code quarkus-bom} itself and carried the
 * table at its own version would ask the lock for a BOM no repository has. Such a table is not
 * written, and one row per table names the modules that build as plain jars instead.
 */
final class SelfHostedFrameworks {

    /**
     * A framework table, the Maven plugin it comes from, the BOM the table implies and the
     * artifactId a reactor builds when it is the framework.
     */
    private record Framework(String table, String plugin, String impliedBom, String reactorBom) {}

    private static final List<Framework> FRAMEWORKS = List.of(
            new Framework("quarkus", "quarkus-maven-plugin", "io.quarkus.platform:quarkus-bom", "quarkus-bom"),
            new Framework(
                    "spring-boot",
                    "spring-boot-maven-plugin",
                    "org.springframework.boot:spring-boot-dependencies",
                    "spring-boot-dependencies"));

    /** How many module paths a row names before counting the rest. */
    private static final int NAMED_PATHS = 6;

    private SelfHostedFrameworks() {}

    /**
     * {@code modules} with every framework table whose version is one the reactor builds the
     * framework's BOM at removed. {@code boms} are the reactor's BOM leaves, which build at
     * {@code reactorVersion}; a BOM that is a module of its own carries its version.
     */
    static Map<String, JkBuild> strip(
            Map<String, JkBuild> modules,
            List<ReactorModules.Leaf> boms,
            String reactorVersion,
            ImportReport.Builder report) {
        Map<String, JkBuild> out = new LinkedHashMap<>(modules);
        for (Framework framework : FRAMEWORKS) {
            Map<String, String> bomVersions = reactorBoms(framework, modules, boms, reactorVersion);
            if (bomVersions.isEmpty()) continue;
            List<String> stripped = new ArrayList<>();
            String version = null;
            for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
                JkBuild module = e.getValue();
                String declared = module.pluginConfig(framework.table())
                        .map(config -> config.string("version"))
                        .orElse(null);
                if (declared == null || !bomVersions.containsValue(declared)) continue;
                out.put(e.getKey(), module.withoutPluginConfig(framework.table()));
                stripped.add(e.getKey());
                version = declared;
            }
            if (version != null) report.warning(row(framework, version, stripped, bomVersions));
        }
        return out;
    }

    /** Path → version of every unit of the reactor that is the framework's BOM. */
    private static Map<String, String> reactorBoms(
            Framework framework, Map<String, JkBuild> modules, List<ReactorModules.Leaf> boms, String reactorVersion) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
            if (e.getValue().project().name().equals(framework.reactorBom())) {
                found.put(e.getKey(), e.getValue().project().version());
            }
        }
        for (ReactorModules.Leaf bom : boms) {
            String artifact = bom.ga().substring(bom.ga().indexOf(':') + 1);
            if (artifact.equals(framework.reactorBom())) found.put(bom.path(), reactorVersion);
        }
        return found;
    }

    private static String row(Framework framework, String version, List<String> paths, Map<String, String> boms) {
        StringBuilder out = new StringBuilder("`")
                .append(framework.plugin())
                .append("` runs at the reactor's own version ")
                .append(version)
                .append(" on ")
                .append(paths.size())
                .append(paths.size() == 1 ? " module (" : " modules (");
        for (int i = 0; i < paths.size() && i < NAMED_PATHS; i++) {
            if (i > 0) out.append(", ");
            out.append(paths.get(i));
        }
        if (paths.size() > NAMED_PATHS)
            out.append(", +").append(paths.size() - NAMED_PATHS).append(" more");
        out.append("), so no `[")
                .append(framework.table())
                .append("]` table is written there: the table would imply the platform BOM ")
                .append(framework.impliedBom())
                .append(':')
                .append(version)
                .append(", which repositories publish for releases only, and this reactor builds `")
                .append(framework.reactorBom())
                .append("` itself (");
        out.append(String.join(", ", boms.keySet()));
        return out.append("). These modules build as plain jars; keep building them with `jk mvn package` for the")
                .append(" framework's own packaging.")
                .toString();
    }
}
