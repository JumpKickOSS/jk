// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reactor parent's managed versions become the workspace's shared entries, and only versions do:
 * a member whose own platform supplies the module keeps its edge as it is.
 */
class ReactorVersionsTest {

    private static final String MODULE = "org.infinispan:infinispan-core";

    @Test
    void a_platform_managed_edge_is_never_hoisted_whichever_member_comes_first() {
        Map<String, JkBuild> members = new LinkedHashMap<>();
        // The first member takes the module from its own BOM, the second from the parent's pin.
        members.put("quarkus/runtime", member("runtime", Dependency.platformManaged("infinispan-core", MODULE)));
        members.put(
                "testsuite/utils",
                member("utils", Dependency.of("infinispan-core", MODULE, VersionSelector.parse("=16.0.14"))));
        Map<String, Set<String>> supplied =
                Map.of("quarkus/runtime", Set.of(MODULE), "testsuite/utils", Set.of(MODULE));

        ReactorVersions.Hoisted hoisted = ReactorVersions.hoist(members, supplied, Set.of());

        assertThat(hoisted.dependencies()).hasEntrySatisfying("infinispan-core", ws -> assertThat(
                        requireNonNull(ws.version()).raw())
                .isEqualTo("=16.0.14"));
        Dependency runtime = requireNonNull(hoisted.members().get("quarkus/runtime"))
                .dependencies()
                .of(Scope.MAIN)
                .getFirst();
        assertThat(runtime.isPlatformManaged())
                .as("its own platform's edge stays")
                .isTrue();
        Dependency utils = requireNonNull(hoisted.members().get("testsuite/utils"))
                .dependencies()
                .of(Scope.MAIN)
                .getFirst();
        assertThat(utils.isWorkspace())
                .as("the parent's pin reads the shared entry")
                .isTrue();
    }

    private static JkBuild member(String name, Dependency dep) {
        return JkBuild.builder(Project.builder("org.keycloak", name, "1.0").build())
                .dependencies(new JkBuild.Dependencies(Map.of(Scope.MAIN, List.of(dep))))
                .build();
    }
}
