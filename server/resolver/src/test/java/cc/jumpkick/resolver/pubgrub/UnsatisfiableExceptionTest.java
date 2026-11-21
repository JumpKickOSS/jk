// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The conflict a failed solve carries, in the JSON form a diagnostic takes it in. */
class UnsatisfiableExceptionTest {

    @Test
    void the_conflict_json_is_the_conflicts_map_as_one_object() {
        UnsatisfiableException e = catchThrowableOfType(
                UnsatisfiableException.class, () -> new PubGrubSolver(InMemoryPackageSource.builder()
                                .version("com.foo:widget", "1.0")
                                .build())
                        .solve("root", "1.0", List.of(Term.positive("com.foo:widget", VersionSet.exact("9.9.9")))));

        Object parsed = requireNonNull(MiniJson.parse(e.conflictJson()));

        assertThat(parsed).isEqualTo(e.conflict().toMap());
        Map<?, ?> conflict = (Map<?, ?>) parsed;
        assertThat(conflict.get("facts")).asInstanceOf(LIST).anySatisfy(fact -> {
            Map<?, ?> row = (Map<?, ?>) fact;
            assertThat(row.get("kind")).isEqualTo("no-versions");
            assertThat(row.get("package")).isEqualTo("com.foo:widget");
        });
    }
}
