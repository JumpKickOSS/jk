// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Every name {@code jk import} writes as a TOML key — a dependency handle, a feature, a profile, a
 * repository, a manifest attribute — reads back through {@link JkBuildParser} as the same string,
 * for every character a Maven artifactId or profile id can carry: letters and digits, {@code _},
 * {@code -}, {@code .}, {@code +}, a space, and a letter outside ASCII.
 */
class JkBuildRendererKeyRoundTripPropertyTest {

    private static final String MAVEN_ID_CHARS = "abcXYZ019_-.+ é";

    @Provide
    Arbitrary<String> mavenIds() {
        return Arbitraries.strings()
                .withChars(MAVEN_ID_CHARS.toCharArray())
                .ofMinLength(1)
                .ofMaxLength(24)
                // A Maven id has a non-blank character; `default` is the [features] list of
                // defaults and `jk-local` the reserved store.
                .filter(s -> !s.isBlank() && !s.equals("default") && !s.equals("jk-local"));
    }

    @Property(tries = 300)
    void a_handle_renders_as_a_key_the_parser_reads_back_verbatim(
            @ForAll("mavenIds") String handle,
            @ForAll("mavenIds") String feature,
            @ForAll("mavenIds") String profile,
            @ForAll("mavenIds") String repository,
            @ForAll("mavenIds") String attribute) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(Dependency.of(handle, "com.acme:" + handle, VersionSelector.parse("1.0"))
                        .withOptional(true)));
        JkBuild model = JkBuild.builder(
                        Project.builder("com.acme", "app", "1.0.0").java(25).build())
                .dependencies(new JkBuild.Dependencies(byScope))
                .features(new Features(Map.of(feature, new Feature(feature, List.of(handle), List.of())), List.of()))
                .profiles(new Profiles(Map.of(profile, new Profile(profile, null, List.of("-Xlint"), List.of()))))
                .repositories(List.of(new RepositorySpec(repository, URI.create("https://repo.example/m2"))))
                .manifest(Map.of(attribute, "value"))
                .build();

        JkBuild reparsed = JkBuildParser.parse(JkBuildRenderer.render(model));

        assertThat(reparsed.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.library()).isEqualTo(handle);
            assertThat(d.module()).isEqualTo("com.acme:" + handle);
        });
        assertThat(requireNonNull(reparsed.features().byName().get(feature)).deps())
                .containsExactly(handle);
        assertThat(requireNonNull(reparsed.profiles().byName().get(profile)).javacArgs())
                .containsExactly("-Xlint");
        assertThat(reparsed.repositories())
                .singleElement()
                .extracting(RepositorySpec::name)
                .isEqualTo(repository);
        assertThat(reparsed.manifest()).containsEntry(attribute, "value");
    }
}
