// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutputViewTest {

    @Test
    void the_lists_are_copied_and_immutable() {
        List<Path> poms = new ArrayList<>(List.of(Path.of("a.pom")));
        List<Path> jars = new ArrayList<>(List.of(Path.of("a.jar"), Path.of("b.jar")));
        OutputView v = new OutputView(poms, jars, null);
        poms.clear();
        jars.clear();
        assertThat(v.poms()).containsExactly(Path.of("a.pom"));
        assertThat(v.jars()).containsExactly(Path.of("a.jar"), Path.of("b.jar"));
        assertThatThrownBy(() -> v.jars().add(Path.of("c.jar"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void coverage_is_optional() {
        assertThat(new OutputView(List.of(), List.of(), null).coverage()).isEmpty();
        assertThat(new OutputView(List.of(), List.of(), Path.of("cov.xml")).coverage())
                .contains(Path.of("cov.xml"));
    }
}
