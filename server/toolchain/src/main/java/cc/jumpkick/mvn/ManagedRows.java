// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report rows for a POM's inline {@code dependencyManagement}: per owning POM, the pins written
 * to {@code [managed-dependencies]}, the entries written with {@code exclude}, the ones with
 * exclusions and no version, and the ones whose version is still a property.
 */
final class ManagedRows {

    private final Map<String, List<String>> pinned = new LinkedHashMap<>();
    private final Map<String, List<String>> excluding = new LinkedHashMap<>();
    private final Map<String, List<String>> versionless = new LinkedHashMap<>();
    private final Map<String, List<String>> unresolved = new LinkedHashMap<>();

    /** A row written for {@code owner}'s entry: counted as a pin, or as an exclusion when it carries one. */
    void written(String owner, Dependency row) {
        (row.exclusions().isEmpty() ? pinned : excluding)
                .computeIfAbsent(owner, k -> new ArrayList<>())
                .add(row.module());
    }

    void versionless(String owner, String module) {
        versionless.computeIfAbsent(owner, k -> new ArrayList<>()).add(module);
    }

    void unresolved(String owner, String module) {
        unresolved.computeIfAbsent(owner, k -> new ArrayList<>()).add(module);
    }

    void flush(ImportReport.Builder report) {
        pinned.forEach((owner, modules) -> report.warning("dependencyManagement in " + owner + ": "
                + count(modules, "unused pin") + " moved to [managed-dependencies] (" + sample(modules) + ")"));
        excluding.forEach((owner, modules) -> report.warning("dependencyManagement in " + owner + ": exclusions on "
                + count(modules, "module") + " moved to [managed-dependencies] (" + sample(modules) + ")"));
        versionless.forEach((owner, modules) -> report.warning("dependencyManagement in " + owner + ": exclusions on "
                + count(modules, "versionless entry") + " apply only where declared (" + sample(modules) + ")"));
        unresolved.forEach((owner, modules) -> report.warning("dependencyManagement in " + owner + ": "
                + count(modules, "pin") + " with unresolved versions dropped (" + sample(modules) + ")"));
    }

    /** The rows for the reactor parents' entries a workspace import writes once on the root. */
    static void reportHoisted(List<Dependency> hoisted, ImportReport.Builder report) {
        List<String> pinned = new ArrayList<>();
        List<String> excluding = new ArrayList<>();
        for (Dependency d : hoisted) (d.exclusions().isEmpty() ? pinned : excluding).add(d.module());
        if (!pinned.isEmpty()) {
            report.warning("parent dependencyManagement: " + count(pinned, "unused pin")
                    + " moved to the root's [managed-dependencies] (" + sample(pinned) + ")");
        }
        if (!excluding.isEmpty()) {
            report.warning("parent dependencyManagement: exclusions on " + count(excluding, "module")
                    + " moved to the root's [managed-dependencies] (" + sample(excluding) + ")");
        }
    }

    static String count(List<String> modules, String noun) {
        int n = modules.size();
        String plural = noun.endsWith("entry") ? noun.substring(0, noun.length() - 1) + "ies" : noun + "s";
        return n + " " + (n == 1 ? noun : plural);
    }

    static String sample(List<String> modules) {
        return modules.size() > 3 ? String.join(", ", modules.subList(0, 3)) + ", …" : String.join(", ", modules);
    }
}
