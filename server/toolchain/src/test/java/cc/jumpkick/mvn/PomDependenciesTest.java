// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PomDependenciesTest {

    private static Dependency dep(String library, String module) {
        return Dependency.of(library, module, VersionSelector.parse("1.0"));
    }

    @Test
    void a_handle_two_groups_share_is_numbered_in_declaration_order_within_one_scope() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                new ArrayList<>(List.of(
                        dep("util", "a:util"),
                        dep("util", "b:util"),
                        dep("util-2", "c:util-2"),
                        dep("util", "d:util"))));
        byScope.put(Scope.TEST, new ArrayList<>(List.of(dep("util", "e:util"))));
        ImportReport.Builder report = ImportReport.builder();

        PomDependencies.uniquifyHandles(byScope, report);

        assertThat(byScope.get(Scope.MAIN))
                .extracting(Dependency::library, Dependency::module)
                .containsExactly(
                        tuple("util", "a:util"),
                        tuple("util-2", "b:util"),
                        tuple("util-2-2", "c:util-2"),
                        tuple("util-3", "d:util"));
        assertThat(byScope.get(Scope.TEST)).extracting(Dependency::library).containsExactly("util");
        assertThat(report.build().issues())
                .extracting(ImportReport.Issue::message)
                .contains("handle util collides in [dependencies]; b:util written as util-2");
    }
}
