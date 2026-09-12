// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Scope;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Round-trip the optional {@code pinned-by} field on {@link Lockfile.Artifact}.
 *
 * <p>{@code pinned-by} records the BOM coord ({@code group:artifact:version}) that constrained a
 * package to its locked version. It's optional — packages that resolved through normal at-least
 * propagation have a {@code null} {@code pinnedBy} and emit no {@code pinned-by} line.
 */
class LockfilePinnedByTest {

    @Test
    void package_with_pinned_by_round_trips() {
        Lockfile original = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "com.fasterxml.jackson.core:jackson-annotations",
                        "2.21",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:abcd",
                        null,
                        List.of(Scope.MAIN),
                        List.of(),
                        "tools.jackson:jackson-bom:3.1.3")));

        String rendered = LockfileWriter.render(original);
        assertThat(rendered).contains("pinned-by = \"tools.jackson:jackson-bom:3.1.3\"");

        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.artifacts()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("com.fasterxml.jackson.core:jackson-annotations");
            assertThat(p.version()).isEqualTo("2.21");
            assertThat(p.pinnedBy()).isEqualTo("tools.jackson:jackson-bom:3.1.3");
        });
    }

    @Test
    void package_without_pinned_by_omits_the_field() {
        Lockfile original = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "com.example:widget",
                        "1.0.0",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:1234",
                        null,
                        List.of(Scope.MAIN),
                        List.of())));

        String rendered = LockfileWriter.render(original);
        assertThat(rendered).doesNotContain("pinned-by");

        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.artifacts().getFirst().pinnedBy()).isNull();
    }

    /** {@code platformPins()} is the lock's memory of which BOM version it resolved. */
    @Test
    void platform_pins_are_read_off_the_managed_rows() {
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        artifact(
                                "org.springframework.boot:spring-boot",
                                "4.1.1",
                                "org.springframework.boot:spring-boot-dependencies:4.1.1"),
                        artifact(
                                "org.slf4j:slf4j-api",
                                "2.0.18",
                                "org.springframework.boot:spring-boot-dependencies:4.1.1"),
                        artifact(
                                "com.fasterxml.jackson.core:jackson-annotations",
                                "2.21",
                                "tools.jackson:jackson-bom:3.1.3"),
                        artifact("com.example:widget", "1.0.0", null)));

        assertThat(lock.platformPins())
                .containsExactly(
                        Map.entry("org.springframework.boot:spring-boot-dependencies", "4.1.1"),
                        Map.entry("tools.jackson:jackson-bom", "3.1.3"));
    }

    private static Lockfile.Artifact artifact(String name, String version, @Nullable String pinnedBy) {
        return new Lockfile.Artifact(
                name,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:abcd",
                null,
                List.of(Scope.MAIN),
                List.of(),
                pinnedBy);
    }
}
