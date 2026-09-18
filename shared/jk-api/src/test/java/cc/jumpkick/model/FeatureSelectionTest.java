// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which optional dependencies a feature selection leaves on a manifest. */
class FeatureSelectionTest {

    private static JkBuild build() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("com.foo:api", VersionSelector.parse("1.0")),
                        new Dependency("com.foo:lz4", VersionSelector.parse("1.0")).withOptional(true),
                        new Dependency("com.foo:mysql", VersionSelector.parse("1.0")).withOptional(true),
                        new Dependency("com.foo:own", VersionSelector.parse("1.0")).withOptional(true)));
        return JkBuild.builder(new Project("com.acme", "a", "1.0", 25))
                .dependencies(new JkBuild.Dependencies(byScope))
                .features(new Features(
                        Map.of(
                                "fast", new Feature("fast", List.of("lz4"), List.of()),
                                "noshade", new Feature("noshade", List.of("mysql"), List.of())),
                        List.of("fast")))
                .build();
    }

    private static List<String> mainHandles(JkBuild build) {
        return build.dependencies().of(Scope.MAIN).stream()
                .map(Dependency::library)
                .toList();
    }

    @Test
    void the_defaults_keep_the_default_features_and_every_optional_no_feature_names() {
        assertThat(mainHandles(FeatureSelection.DEFAULTS.apply(build()))).containsExactly("api", "lz4", "own");
    }

    @Test
    void a_requested_feature_adds_its_dependencies_and_no_defaults_removes_the_default_ones() {
        assertThat(mainHandles(new FeatureSelection(List.of("noshade"), true).apply(build())))
                .containsExactly("api", "lz4", "mysql", "own");
        assertThat(mainHandles(new FeatureSelection(List.of(), false).apply(build())))
                .containsExactly("api", "own");
        assertThat(mainHandles(new FeatureSelection(List.of("noshade"), false).apply(build())))
                .containsExactly("api", "mysql", "own");
    }

    @Test
    void a_name_the_manifest_does_not_declare_is_left_out_for_it() {
        JkBuild build = build();
        assertThat(mainHandles(new FeatureSelection(List.of("elsewhere"), true).apply(build)))
                .containsExactly("api", "lz4", "own");
        JkBuild featureless = JkBuild.builder(build.project())
                .dependencies(build.dependencies())
                .build();
        assertThat(new FeatureSelection(List.of("elsewhere"), false).apply(featureless))
                .as("a manifest with no [features] table is returned as it is")
                .isSameAs(featureless);
    }
}
