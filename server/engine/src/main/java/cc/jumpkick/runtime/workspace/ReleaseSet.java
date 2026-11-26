// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.runtime.ModuleJar;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What {@code jk publish} and {@code jk install} ship of a workspace when members say {@code
 * [publish] skip = true}: every selected module with a jar but those, and a refusal, before
 * anything builds, for a shipped module whose POM would name a skipped sibling — a consumer of it
 * could resolve neither from the repository it went to nor from this machine's installs. A
 * publish's release lane adds its own refusals ({@link ReleaseLane#refusals}).
 */
final class ReleaseSet {

    private ReleaseSet() {}

    /** The scopes a consumer resolves from a POM: a sibling named in one of them must ship too. */
    static final List<Scope> CONSUMED_SCOPES = List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);

    /**
     * One line per shipped module and skipped sibling it names in a {@link #CONSUMED_SCOPES} table;
     * empty for a run that is no install or publish, and when every sibling a shipped module needs
     * ships with it.
     */
    static List<String> refusals(WorkspaceRequest request, Map<Path, JkBuild> units, Predicate<Path> selected) {
        List<String> refused = new ArrayList<>();
        WorkspaceTarget target = request.target();
        WorkspaceSpec spec = request.spec() == null ? WorkspaceSpec.DEFAULT : request.spec();
        // A publish without the library lane ships no POM: only the release lane's refusals apply.
        if (target == WorkspaceTarget.PUBLISH
                && !(spec.tail() instanceof TailArgs.Publish p && p.runs(PublishLane.LIBRARY)))
            return ReleaseLane.refusals(request, units, selected);
        if (target == WorkspaceTarget.PUBLISH
                || target == WorkspaceTarget.INSTALL
                || target == WorkspaceTarget.RESHELVE) {
            for (Map.Entry<Path, JkBuild> unit : units.entrySet()) {
                Path dir = unit.getKey();
                JkBuild manifest = unit.getValue();
                if (!selected.test(dir) || ModuleJar.shippedStep(manifest, dir) == null) continue;
                if (target != WorkspaceTarget.PUBLISH
                        && !InstallPlans.installsToM2(manifest, dir, spec.installDestination())) continue;
                String reason = ModuleJar.mavenRefusal(manifest, dir);
                if (reason != null) refused.add(reason);
            }
        }
        refused.addAll(installRefusals(request, units, selected));
        refused.addAll(skippedDependencies(request, units, selected));
        if (refused.isEmpty()) refused.addAll(ReleaseLane.refusals(request, units, selected));
        return refused;
    }

    /**
     * What an install would refuse, said before anything builds: a snapshot whose release is
     * already installed where the snapshot would go, so the snapshot could never be chosen. A
     * release rebuilt to other bytes is left to the install step, which compares the bytes: a
     * rebuild after an engine change reproduces them, and only the comparison can tell.
     */
    static List<String> installRefusals(WorkspaceRequest request, Map<Path, JkBuild> units, Predicate<Path> selected) {
        if (request.target() != WorkspaceTarget.INSTALL) return List.of();
        WorkspaceSpec spec = request.spec() == null ? WorkspaceSpec.DEFAULT : request.spec();
        List<String> refused = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> unit : units.entrySet()) {
            Path dir = unit.getKey();
            if (!selected.test(dir) || ModuleJar.shippedStep(unit.getValue(), dir) == null) continue;
            String hidden =
                    InstallPlans.shadowedByRelease(unit.getValue(), dir, spec.m2Dir(), spec.installDestination());
            if (hidden != null) refused.add(hidden);
        }
        return refused;
    }

    /** {@link #refusals}' shipped-module-names-a-skipped-sibling half. */
    static List<String> skippedDependencies(
            WorkspaceRequest request, Map<Path, JkBuild> units, Predicate<Path> selected) {
        String verb = verb(request);
        if (verb == null) return List.of();
        Map<String, Path> byCoord = new LinkedHashMap<>();
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> u : units.entrySet()) {
            byCoord.put(
                    u.getValue().project().group() + ":"
                            + u.getValue().project().name(),
                    u.getKey());
            byName.put(u.getValue().project().name(), u.getKey());
        }
        List<String> refused = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> unit : units.entrySet()) {
            Path dir = unit.getKey();
            JkBuild manifest = unit.getValue();
            if (!selected.test(dir) || ModuleJar.shippedStep(manifest, dir) == null) continue;
            Map<Path, JkBuild> named = new LinkedHashMap<>();
            for (Scope scope : CONSUMED_SCOPES) {
                for (Dependency d : manifest.dependencies().of(scope)) {
                    Path sibling = ModuleOrder.resolveSibling(d, byCoord, byName);
                    JkBuild other = sibling == null || sibling.equals(dir) ? null : units.get(sibling);
                    if (sibling != null && other != null && ModuleJar.skipsRelease(other)) named.put(sibling, other);
                }
            }
            for (Map.Entry<Path, JkBuild> sibling : named.entrySet()) {
                refused.add(refusal(verb, request, manifest, sibling.getValue(), sibling.getKey()));
            }
        }
        return refused;
    }

    /**
     * The note a run that leaves modules out says once: which selected members {@code [publish]
     * skip} keeps back, or null when none does.
     */
    static @Nullable String note(WorkspaceRequest request, Map<Path, JkBuild> units, Predicate<Path> selected) {
        String verb = verb(request);
        if (verb == null) return null;
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> unit : units.entrySet()) {
            if (selected.test(unit.getKey()) && ModuleJar.skipsRelease(unit.getValue())) {
                skipped.add(unit.getValue().project().name());
            }
        }
        if (skipped.isEmpty()) return null;
        return "[publish] skip keeps " + skipped.size() + (skipped.size() == 1 ? " module" : " modules") + " out of jk "
                + verb + ": " + String.join(", ", skipped);
    }

    private static String refusal(
            String verb, WorkspaceRequest request, JkBuild unit, JkBuild sibling, Path siblingDir) {
        String name = unit.project().name();
        String other = sibling.project().name();
        return name + " depends on " + other + ", which [publish] skip keeps out of jk " + verb
                + ", so nothing that uses " + name + " could resolve it; remove skip from "
                + display(request, siblingDir) + ", or make " + other + " a [test-dependencies] entry of "
                + name;
    }

    private static String display(WorkspaceRequest request, Path dir) {
        Path manifest = ManifestPaths.manifestIn(dir);
        try {
            return request.entryDir()
                    .toAbsolutePath()
                    .normalize()
                    .relativize(manifest.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException otherRoot) {
            return manifest.toString();
        }
    }

    private static @Nullable String verb(WorkspaceRequest request) {
        if (request.target() == WorkspaceTarget.INSTALL) return "install";
        if (request.target() == WorkspaceTarget.PUBLISH) return "publish";
        return null;
    }
}
