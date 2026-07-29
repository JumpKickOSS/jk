// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * JK-1271: where {@code ${VAR}} is honoured, and that everywhere else it is a loud error.
 *
 * <p>The whitelist is the reproducibility fence. {@code jk.toml} plus {@code jk.lock} must fully
 * describe the artifact, and the action cache makes a leak worse than plain non-determinism: an
 * environment-sourced value inside a cache key means CI and a laptop never share cache, and one that
 * reaches a compiler flag <em>without</em> reaching the key produces silently stale artifacts.
 */
class InterpolationTest {

    private static final String PROJECT =
            """
            [project]
            group   = "com.example"
            name    = "m"
            version = "1.0.0"
            """;

    private static void parse(String toml) {
        JkBuildParser.parse(toml);
    }

    // ---- allowed ----------------------------------------------------------------

    @Test
    void repository_credentials_may_interpolate() {
        assertThatCode(() -> parse(PROJECT
                        + """
                        [repositories.r]
                        url = "https://nexus.example/repo/"
                        username = "${REPO_USER}"
                        password = "${REPO_PASS}"
                        """))
                .doesNotThrowAnyException();
    }

    @Test
    void repository_object_store_keys_may_interpolate() {
        assertThatCode(() -> parse(PROJECT
                        + """
                        [repositories.s3]
                        url = "s3://bucket/maven"
                        access-key = "${AWS_KEY}"
                        secret-key = "${AWS_SECRET}"
                        session-token = "${AWS_TOKEN}"
                        region = "${AWS_REGION}"
                        endpoint = "${AWS_ENDPOINT}"
                        """))
                .doesNotThrowAnyException();
    }

    @Test
    void test_env_values_may_interpolate() {
        assertThatCode(() -> parse(PROJECT
                        + """
                        [test]
                        env = { TOKEN = "${CI_TOKEN}", HOME_DIR = "${target}/h" }
                        """))
                .doesNotThrowAnyException();
    }

    // ---- refused, because these decide what gets built -------------------------

    @Test
    void a_repository_url_may_not_interpolate() {
        // The lockfile records the URL, so an env-dependent one makes a committed lock differ
        // between machines built from the same commit (JK-1272).
        assertThatThrownBy(() -> parse(PROJECT + """
                        [repositories]
                        r = "${MIRROR}/maven"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.r")
                .hasMessageContaining("MIRROR");
    }

    @Test
    void a_url_inside_a_repository_table_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT
                        + """
                        [repositories.r]
                        url = "${MIRROR}/maven"
                        username = "${REPO_USER}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.r.url");
    }

    @Test
    void a_version_may_not_interpolate() {
        assertThatThrownBy(() -> parse("""
                        [project]
                        group   = "com.example"
                        name    = "m"
                        version = "${MY_VERSION}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.version");
    }

    @Test
    void a_dependency_coordinate_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT
                        + """
                        [dependencies]
                        thing = { group = "com.example", name = "thing", version = "${DEP_VERSION}" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("dependencies.thing.version");
    }

    @Test
    void compile_shaping_build_keys_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [build]
                        extra-src = ["${EXTRA_SRC}"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("build.extra-src");

        assertThatThrownBy(() -> parse(PROJECT + """
                        [build]
                        ksp-options = ["room.schemaLocation=${SCHEMA_DIR}"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("build.ksp-options");
    }

    @Test
    void extra_resources_inside_an_array_of_tables_may_not_interpolate() {
        // Array elements are walked too, so a reference cannot hide inside one.
        assertThatThrownBy(() -> parse(PROJECT
                        + """
                        [build]
                        extra-resources = [ { from = "${SRC}/x.txt", into = "d" } ]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("extra-resources");
    }

    @Test
    void a_jar_manifest_attribute_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [manifest]
                        Build-Number = "${CI_BUILD}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("manifest.Build-Number");
    }

    @Test
    void the_error_names_every_offending_position() {
        assertThatThrownBy(() -> parse("""
                        [project]
                        group   = "com.example"
                        name    = "m"
                        version = "${A}"

                        [manifest]
                        X = "${B}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.version")
                .hasMessageContaining("manifest.X");
    }

    // ---- shape of the matcher --------------------------------------------------

    @Test
    void a_dollar_that_is_not_a_reference_is_left_alone() {
        // A literal '$' — a password rule, a shell snippet — must not trip the guard.
        assertThatCode(() -> parse(PROJECT + """
                        [manifest]
                        Note = "costs $5 and $ is fine"
                        """))
                .doesNotThrowAnyException();
    }

    @Test
    void whitelist_matching_is_exact_about_depth_and_names() {
        assertThat(Interpolation.allowed("repositories.r.username")).isTrue();
        assertThat(Interpolation.allowed("test.env.ANYTHING")).isTrue();
        // Not a credential field.
        assertThat(Interpolation.allowed("repositories.r.url")).isFalse();
        // Right leaf name, wrong place.
        assertThat(Interpolation.allowed("build.username")).isFalse();
        // An array element is never a whitelisted slot.
        assertThat(Interpolation.allowed("test.env[0].X")).isFalse();
    }

    @Test
    void expand_is_strict_about_an_unset_variable() {
        assertThatThrownBy(() -> Interpolation.expand("${NOPE}", "[test].env.X", name -> null))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].env.X")
                .hasMessageContaining("NOPE");

        assertThat(Interpolation.expand("${A}-x", "pos", name -> "v")).isEqualTo("v-x");
        assertThat(Interpolation.expand("plain", "pos", name -> null)).isEqualTo("plain");
        assertThat(Interpolation.expand(null, "pos", name -> null)).isNull();
    }
}
