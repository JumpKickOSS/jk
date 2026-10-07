// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The versions a reactor parent's own {@code <dependencyManagement>} supplies to the modules that
 * declare a dependency without one, written as the workspace's: one root {@code
 * [workspace.dependencies]} entry per module and {@code x.workspace = true} on each member that
 * inherited it. A version a module writes itself stays that member's pin. A dependency the root
 * table cannot carry as written — a classifier, a test jar, a handle a sibling's name or another
 * coordinate already takes — keeps its version.
 */
final class ReactorVersions {

    private ReactorVersions() {}

    /** The members with their inherited versions read from the workspace, and the root's shared entries. */
    record Hoisted(Map<String, JkBuild> members, Map<String, Workspace.WorkspaceDependency> dependencies) {}

    /**
     * {@code members} with each dependency on a module of {@code supplied} (by member path) read
     * from the root, and the entries the root carries for them. {@code reactor} are the {@code
     * group:artifact}s the reactor builds, which are sibling edges and never shared versions.
     */
    static Hoisted hoist(Map<String, JkBuild> members, Map<String, Set<String>> supplied, Set<String> reactor) {
        Set<String> siblingNames = new HashSet<>();
        for (JkBuild member : members.values())
            siblingNames.add(member.project().name());
        Map<String, Workspace.WorkspaceDependency> shared = new LinkedHashMap<>();
        Map<String, JkBuild> out = new LinkedHashMap<>();
        for (Map.Entry<String, JkBuild> e : members.entrySet()) {
            Set<String> mine = supplied.getOrDefault(e.getKey(), Set.of());
            out.put(
                    e.getKey(),
                    mine.isEmpty() ? e.getValue() : hoist(e.getValue(), mine, reactor, siblingNames, shared));
        }
        return new Hoisted(out, shared);
    }

    private static JkBuild hoist(
            JkBuild member,
            Set<String> supplied,
            Set<String> reactor,
            Set<String> siblingNames,
            Map<String, Workspace.WorkspaceDependency> shared) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Map<String, String> claimed = new HashMap<>();
        shared.forEach((handle, entry) -> claimed.put(
                handle,
                entry.module() + "@" + Objects.requireNonNull(entry.version()).raw()));
        for (Map.Entry<Scope, List<Dependency>> e :
                member.dependencies().byScope().entrySet()) {
            List<Dependency> deps = new ArrayList<>(e.getValue().size());
            for (Dependency d : e.getValue()) {
                deps.add(sharable(e.getKey(), d, supplied, reactor, siblingNames) ? shared(d, claimed, shared) : d);
            }
            byScope.put(e.getKey(), deps);
        }
        return member.withDependencies(new JkBuild.Dependencies(byScope));
    }

    private static boolean sharable(
            Scope scope, Dependency d, Set<String> supplied, Set<String> reactor, Set<String> siblingNames) {
        return scope != Scope.PLATFORM
                && scope != Scope.MANAGED
                && supplied.contains(d.module())
                && !reactor.contains(d.module())
                && d.version() instanceof VersionSelector.Exact
                // A member's own platform supplies that version; a shared entry must carry one.
                && !d.isPlatformManaged()
                && !d.isWorkspace()
                && !d.isGit()
                && !d.isPath()
                && !d.isFile()
                && d.classifier() == null
                && d.kind() == DependencyKind.MAIN
                && !siblingNames.contains(d.library());
    }

    /** {@code d} as a workspace edge, its entry added to {@code shared}, or {@code d} where the handle is taken. */
    private static Dependency shared(
            Dependency d, Map<String, String> claimed, Map<String, Workspace.WorkspaceDependency> shared) {
        String said = d.module() + "@" + d.version().raw();
        String held = claimed.putIfAbsent(d.library(), said);
        if (held != null && !held.equals(said)) return d;
        shared.putIfAbsent(d.library(), new Workspace.WorkspaceDependency(d.group(), d.name(), d.version(), null));
        return Dependency.workspace(d.library()).withOptional(d.optional()).withExclusions(d.exclusions());
    }
}
