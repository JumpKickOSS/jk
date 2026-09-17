// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The describe-reply decode — exactly what the content-keyed cache file replays. */
class PluginDeclarationsTest {

    @Test
    void decodes_steps_packager_and_verbs() {
        var decls = PluginDeclarations.decode(List.of(
                "{\"t\":\"step\",\"name\":\"gen\",\"after\":\"compile\",\"before\":\"package\","
                        + "\"inputs\":[\"classes\"],\"outputs\":[\"out\"],\"contributesClasses\":[\"out\"],"
                        + "\"contributesResources\":[],\"contributesSources\":[]}",
                "{\"t\":\"packager\",\"name\":\"pkg\",\"inputs\":[\"classes\"]}",
                "{\"t\":\"command\",\"name\":\"devices\",\"description\":\"list attached devices\"}",
                "{\"t\":\"label\",\"text\":\"noise\"}"));
        assertThat(decls.steps()).hasSize(1);
        assertThat(requireNonNull(decls.step("gen")).contributesClasses()).containsExactly("out");
        assertThat(requireNonNull(decls.packager()).name()).isEqualTo("pkg");
        assertThat(requireNonNull(decls.step("gen")).contributesTestSources()).isEmpty();
        assertThat(decls.commands()).hasSize(1);
        assertThat(requireNonNull(decls.command("devices")).description()).isEqualTo("list attached devices");
        assertThat(decls.command("nope")).isNull();
    }
}
