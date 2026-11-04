// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RenameHintsTest {

    @Test
    void aWrongPackageIsAnsweredWithTheTypeOfThatSimpleNameThatDeclaresTheMethod() {
        String reply = RenameHints.noMethod(
                "org.acme.graph.CodeGraph.between",
                "org.acme.graph.CodeGraph",
                "between",
                List.of("cc/jumpkick/codegraph/CodeGraph", "cc/jumpkick/other/Range", "a/Outer$CodeGraph"));
        assertThat(reply)
                .isEqualTo("No method 'org.acme.graph.CodeGraph.between' in the compiled classes. Did you mean"
                        + " a.Outer.CodeGraph.between or cc.jumpkick.codegraph.CodeGraph.between? Pass that.");
        assertThat(RenameHints.withUnbuilt(reply, List.of("plugins/android")))
                .as("a reply that names the method meant does not send the agent to build")
                .isEqualTo(reply);
    }

    @Test
    void withNoTypeOfThatNameTheUnbuiltModulesAreCountedNotListed() {
        String reply = RenameHints.noMethod("Nope.go", "Nope", "go", List.of("a/Other"));
        assertThat(reply).isEqualTo("No method 'Nope.go' in the compiled classes. Pass Type.method.");
        assertThat(RenameHints.withUnbuilt(reply, List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7")))
                .isEqualTo(reply + " 7 modules have no compiled classes (m1, m2, m3, m4, m5 and 2 more): build"
                        + " them first (run kind=build, or jk build).");
    }
}
