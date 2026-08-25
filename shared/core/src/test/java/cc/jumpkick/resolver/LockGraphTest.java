// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The shared substrate contract: one adjacency, aliased lookups, both walk directions agree. */
class LockGraphTest {

    @Test
    void forward_strips_versions_and_keeps_lock_order_while_sorted_orders_naturally() {
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:zeta@1.0", "com.foo:alpha@1.0")),
                pkg("com.foo:zeta", "1.0", List.of()),
                pkg("com.foo:alpha", "1.0", List.of()));
        LockGraph g = LockGraph.forLock(lock);

        assertThat(g.forward("com.foo:root")).containsExactly("com.foo:zeta", "com.foo:alpha");
        assertThat(g.forwardSorted("com.foo:root")).containsExactly("com.foo:alpha", "com.foo:zeta");
        assertThat(g.forward("com.foo:absent")).isEmpty();
    }

    @Test
    void lookups_alias_name_package_key_and_ga() {
        Lockfile lock = lockOf(pkg("com.foo:lib", "2.0", List.of()));
        LockGraph g = LockGraph.forLock(lock);

        var byName = g.artifact("com.foo:lib");
        assertThat(byName).isNotNull();
        assertThat(g.artifact(byName.packageKey())).isSameAs(byName);
        assertThat(g.artifact(LockGraph.ga(byName.packageKey()))).isSameAs(byName);
    }

    @Test
    void parents_inverts_forward_with_ga_aliasing() {
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:mid@1.0")),
                pkg("com.foo:mid", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));
        LockGraph g = LockGraph.forLock(lock);

        assertThat(g.parents("com.foo:leaf")).containsExactly("com.foo:mid");
        assertThat(g.parents("com.foo:mid")).containsExactly("com.foo:root");
        assertThat(g.parents("com.foo:root")).isEmpty();
        // Every forward edge has its reverse edge — the two directions cannot disagree.
        for (Lockfile.Artifact a : lock.artifacts()) {
            for (String child : g.forward(a.name())) {
                assertThat(g.parents(child)).contains(a.name());
            }
        }
    }

    @Test
    void declared_roots_match_key_and_ga_forms() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));
        LockGraph g = LockGraph.of(project, lock, null);

        assertThat(g.isDeclaredRoot("com.foo:root")).isTrue();
        assertThat(g.isDeclaredRoot("com.foo:other")).isFalse();
        assertThat(LockGraph.forLock(lock).isDeclaredRoot("com.foo:root")).isFalse();
    }

    private static JkBuild projectWithMainDeps(String... modules) {
        var deps = new ArrayList<Dependency>();
        for (String m : modules) {
            deps.add(new Dependency(m, new VersionSelector.Exact("=1.0", "1.0")));
        }
        return new JkBuild(
                new Project("com.example", "widget", "0.1.0", 0), new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));
    }

    private static Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Artifact pkg(String module, String version, List<String> deps) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", "sha256:dummy", null, deps);
    }
}
