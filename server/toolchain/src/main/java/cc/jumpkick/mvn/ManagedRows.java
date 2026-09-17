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
        pinned.forEach((owner, modules) -> report.warning("`<dependencyManagement>` in " + owner + " pins "
                + count(modules, "version") + " no declared dependency uses (" + sample(modules)
                + "); written to [managed-dependencies], so they govern transitive versions as they do under"
                + " Maven."));
        excluding.forEach((owner, modules) -> report.warning("`<dependencyManagement>` in " + owner
                + " excludes coordinates under " + count(modules, "module") + " (" + sample(modules)
                + "); written to [managed-dependencies] with `exclude`, so every edge onto the module drops them"
                + " as it does under Maven."));
        versionless.forEach((owner, modules) -> report.warning("`<dependencyManagement>` in " + owner
                + " carries " + count(modules, "entry") + " with exclusions and no version (" + sample(modules)
                + "); jk has no versionless constraint, so the exclusions reach only a dependency that declares"
                + " the module."));
        unresolved.forEach((owner, modules) -> report.warning("`<dependencyManagement>` in " + owner + " pins "
                + count(modules, "version") + " no declared dependency uses whose version is still a property ("
                + sample(modules) + "); no [managed-dependencies] row is written for them."));
    }

    /** The rows for the reactor parents' entries a workspace import writes once on the root. */
    static void reportHoisted(List<Dependency> hoisted, ImportReport.Builder report) {
        List<String> pinned = new ArrayList<>();
        List<String> excluding = new ArrayList<>();
        for (Dependency d : hoisted) (d.exclusions().isEmpty() ? pinned : excluding).add(d.module());
        if (!pinned.isEmpty()) {
            report.warning("`<dependencyManagement>` of the reactor's parent POMs pins " + count(pinned, "version")
                    + " no module declares (" + sample(pinned) + "); written once to the root's"
                    + " [managed-dependencies], so they govern every member's transitive versions as they do under"
                    + " Maven.");
        }
        if (!excluding.isEmpty()) {
            report.warning("`<dependencyManagement>` of the reactor's parent POMs excludes coordinates under "
                    + count(excluding, "module") + " (" + sample(excluding) + "); written once to the root's"
                    + " [managed-dependencies] with `exclude`, so every member's edge onto the module drops them"
                    + " as it does under Maven.");
        }
    }

    static String count(List<String> modules, String noun) {
        int n = modules.size();
        String plural = noun.equals("entry") ? "entries" : noun + "s";
        return n + " " + (n == 1 ? noun : plural);
    }

    static String sample(List<String> modules) {
        return modules.size() > 5 ? String.join(", ", modules.subList(0, 5)) + ", …" : String.join(", ", modules);
    }
}
